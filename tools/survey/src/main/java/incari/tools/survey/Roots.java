package incari.tools.survey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates a synthetic entry point that calls every method of every client class.
 *
 * <p>Following the game from {@code main} alone reaches only part of it: TeaVM resolves a
 * virtual call by the types that actually flow to it, so a screen created in an async
 * callback, or a packet handler behind the network layer, looks unreachable once those are
 * stubbed. The survey is meant to scope the whole client, so every method is made a root:
 * static methods by {@code invokestatic}, constructors by {@code new}, everything else by
 * {@code invokespecial} on a null receiver. TeaVM doesn't verify bytecode, so this links
 * each method directly, private ones included, whatever its runtime receiver would be.
 *
 * <p>Each client class gets its own root method, so a call TeaVM decides never returns only
 * hides the rest of that one class.
 */
final class Roots {

    static final String ENTRY = "incari.survey.roots.Entry";
    private static final String PACKAGE = "incari/survey/roots/";
    /** Keeps each generated class well inside the constant-pool and method-count limits. */
    private static final int CLASSES_PER_HOLDER = 150;

    private Roots() {}

    /**
     * Writes the roots for {@code jar} into {@code dir}. {@code rooted} decides which classes
     * become roots; {@code mainClass} is still called first, as the real entry point.
     *
     * @return the number of classes rooted
     */
    static int generate(Path jar, Predicate<String> rooted, String mainClass, Path dir) throws IOException {
        List<byte[]> classes = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")
                        || name.startsWith("META-INF/")
                        || name.endsWith("package-info.class")
                        || name.endsWith("module-info.class")) {
                    continue;
                }
                if (!rooted.test(name.substring(0, name.length() - 6).replace('/', '.'))) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    classes.add(in.readAllBytes());
                }
            }
        }

        List<String> holders = new ArrayList<>();
        for (int start = 0; start < classes.size(); start += CLASSES_PER_HOLDER) {
            String holder = PACKAGE + "Roots" + holders.size();
            byte[] bytes = holder(holder, classes.subList(start, Math.min(start + CLASSES_PER_HOLDER, classes.size())));
            write(dir, holder, bytes);
            holders.add(holder);
        }
        write(dir, ENTRY.replace('.', '/'), entry(mainClass, holders));
        return classes.size();
    }

    private static byte[] entry(String mainClass, List<String> holders) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ENTRY.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor mv =
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, mainClass.replace('.', '/'), "main", "([Ljava/lang/String;)V", false);
        for (String holder : holders) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, holder, "run", "()V", false);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] holder(String name, List<byte[]> classes) {
        // Straight-line code with no branches needs no stack map frames, so COMPUTE_MAXS is enough.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        for (int i = 0; i < classes.size(); i++) {
            String method = "c" + i;
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, method, "()V", null, null);
            mv.visitCode();
            ClassReader reader = new ClassReader(classes.get(i));
            reader.accept(new CallEveryMethod(mv, reader.getClassName()), ClassReader.SKIP_CODE);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, name, method, "()V", false);
        }
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Emits one call per method of the visited class into {@code out}. */
    private static final class CallEveryMethod extends ClassVisitor {
        private final MethodVisitor out;
        private final String owner;
        private boolean isInterface;
        private boolean isAbstract;

        CallEveryMethod(MethodVisitor out, String owner) {
            super(Opcodes.ASM9);
            this.out = out;
            this.owner = owner;
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                @Nullable String signature,
                @Nullable String superName,
                String @Nullable [] interfaces) {
            isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions) {
            if (name.equals("<clinit>") || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return null;
            }
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            boolean constructor = name.equals("<init>");
            if (constructor && !isAbstract) {
                // Instantiating also puts the class's type into circulation for virtual calls.
                out.visitTypeInsn(Opcodes.NEW, owner);
                out.visitInsn(Opcodes.DUP);
                pushDefaults(descriptor);
                out.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, descriptor, false);
                out.visitInsn(Opcodes.POP);
                return null;
            }
            if (!isStatic) {
                out.visitInsn(Opcodes.ACONST_NULL);
            }
            pushDefaults(descriptor);
            out.visitMethodInsn(
                    isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name, descriptor, isInterface);
            Type result = Type.getReturnType(descriptor);
            if (result.getSize() == 2) {
                out.visitInsn(Opcodes.POP2);
            } else if (result.getSize() == 1) {
                out.visitInsn(Opcodes.POP);
            }
            return null;
        }

        private void pushDefaults(String descriptor) {
            for (Type arg : Type.getArgumentTypes(descriptor)) {
                switch (arg.getSort()) {
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> out.visitInsn(Opcodes.ICONST_0);
                    case Type.LONG -> out.visitInsn(Opcodes.LCONST_0);
                    case Type.FLOAT -> out.visitInsn(Opcodes.FCONST_0);
                    case Type.DOUBLE -> out.visitInsn(Opcodes.DCONST_0);
                    default -> out.visitInsn(Opcodes.ACONST_NULL);
                }
            }
        }
    }

    private static void write(Path dir, String internalName, byte[] bytes) throws IOException {
        Path file = dir.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
