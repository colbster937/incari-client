package incari.tools.survey;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodReference;

/**
 * Stand-ins that let TeaVM's analysis continue past things that are missing.
 *
 * <p>TeaVM treats a call to a missing method as a throw, and its optimizer then drops
 * everything after the call. One missing {@code Runtime.addShutdownHook} in {@code main}
 * hides the entire game from the survey. So the survey runs in rounds: whatever TeaVM
 * reports missing gets a stub that returns a default value, and the compile runs again,
 * until nothing new turns up.
 *
 * <p>The stubs are only an analysis device. Everything stubbed is still reported as a
 * finding; nothing here says the stub is the right fix.
 *
 * <p>Two kinds of stub:
 * <ul>
 *   <li>classes TeaVM can't see (from libraries the rules leave out, or host-JDK classes its
 *       class library lacks) are generated as class files, with every member the game
 *       references, on a directory added to TeaVM's classpath;
 *   <li>members missing from classes that do exist (TeaVM's class library, the host JDK) are
 *       added by {@link StubTransformer} as TeaVM loads each class.
 * </ul>
 */
final class Stubs {

    /** A member to add to an existing class. {@code nameAndDesc}: {@code "foo(I)V"} or {@code "bar:I"}. */
    record ExtraMember(String nameAndDesc, boolean method, boolean isStatic) {}

    private final ReferenceIndex index;
    private final Map<String, Set<ReferenceIndex.Member>> absentClasses = new LinkedHashMap<>();
    private final Map<String, Set<ExtraMember>> extraMembers = new HashMap<>();

    Stubs(ReferenceIndex index) {
        this.index = index;
    }

    int absentClassCount() {
        return absentClasses.size();
    }

    int extraMemberCount() {
        return extraMembers.values().stream().mapToInt(Set::size).sum();
    }

    Map<String, Set<ExtraMember>> extraMembers() {
        return extraMembers;
    }

    /** Stubs an absent class with every member the game references on it. Returns true if new. */
    boolean addAbsentClass(String className) {
        if (absentClasses.containsKey(className)) {
            return false;
        }
        Set<ReferenceIndex.Member> members = new LinkedHashSet<>();
        index.membersOf(className).forEach(members::add);
        absentClasses.put(className, members);
        return true;
    }

    /** Adds a method TeaVM reported missing. Returns true if it wasn't stubbed yet. */
    boolean addMethod(MethodReference method, boolean ownerAbsent) {
        String nameAndDesc = method.getName() + method.getDescriptor().signatureToString();
        return addMember(method.getClassName(), nameAndDesc, true, ownerAbsent);
    }

    boolean addField(FieldReference field, boolean ownerAbsent) {
        return addMember(field.getClassName(), field.getFieldName() + ":" + fieldDescriptor(field), false, ownerAbsent);
    }

    /** TeaVM's error doesn't carry the field's type: take it from a call site, else the JDK. */
    private String fieldDescriptor(FieldReference field) {
        String prefix = field.getFieldName() + ":";
        for (ReferenceIndex.Member member : index.membersOf(field.getClassName())) {
            if (!member.method() && member.nameAndDesc().startsWith(prefix)) {
                return member.nameAndDesc().substring(prefix.length());
            }
        }
        try {
            Class<?> cls = Class.forName(field.getClassName(), false, ClassLoader.getPlatformClassLoader());
            return Type.getDescriptor(cls.getDeclaredField(field.getFieldName()).getType());
        } catch (ClassNotFoundException | NoSuchFieldException | LinkageError e) {
            return "Ljava/lang/Object;";
        }
    }

    private boolean addMember(String owner, String nameAndDesc, boolean method, boolean ownerAbsent) {
        boolean isStatic = isStatic(owner, nameAndDesc, method);
        if (ownerAbsent) {
            boolean added = addAbsentClass(owner);
            Set<ReferenceIndex.Member> members = absentClasses.get(owner);
            if (members == null) {
                throw new IllegalStateException("addAbsentClass didn't register " + owner);
            }
            return members.add(new ReferenceIndex.Member(owner, nameAndDesc, method, isStatic)) || added;
        }
        return extraMembers
                .computeIfAbsent(owner, k -> new LinkedHashSet<>())
                .add(new ExtraMember(nameAndDesc, method, isStatic));
    }

    /** Static-ness from the game's call sites, else from the host JDK's real class, else instance. */
    private boolean isStatic(String owner, String nameAndDesc, boolean method) {
        Boolean fromIndex = index.isStatic(owner, nameAndDesc);
        if (fromIndex != null) {
            return fromIndex;
        }
        try {
            Class<?> cls = Class.forName(owner, false, ClassLoader.getPlatformClassLoader());
            if (method) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (nameAndDesc.equals(m.getName() + Type.getMethodDescriptor(m))) {
                        return Modifier.isStatic(m.getModifiers());
                    }
                }
            } else {
                String name = nameAndDesc.substring(0, nameAndDesc.indexOf(':'));
                return Modifier.isStatic(cls.getDeclaredField(name).getModifiers());
            }
        } catch (ClassNotFoundException | NoSuchFieldException | LinkageError e) {
            // Not a JDK class, or not a member of it: fall through to the default.
        }
        return false;
    }

    /** Writes every absent-class stub as a class file under {@code dir}. */
    void writeClasses(Path dir) throws IOException {
        for (Map.Entry<String, Set<ReferenceIndex.Member>> entry : absentClasses.entrySet()) {
            byte[] bytes = generate(entry.getKey(), entry.getValue());
            Path file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }

    private byte[] generate(String className, Set<ReferenceIndex.Member> members) {
        boolean isInterface = isInterface(className);
        String internal = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object"; // stubs never merge types; avoid loading anything
            }
        };
        int access = Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        cw.visit(Opcodes.V17, access, internal, null, "java/lang/Object", null);

        boolean hasDefaultConstructor = false;
        for (ReferenceIndex.Member member : members) {
            if (!member.method()) {
                String[] parts = member.nameAndDesc().split(":", 2);
                int fieldAccess = Opcodes.ACC_PUBLIC | (member.isStatic() || isInterface ? Opcodes.ACC_STATIC : 0);
                cw.visitField(fieldAccess, parts[0], parts[1], null, null).visitEnd();
                continue;
            }
            int paren = member.nameAndDesc().indexOf('(');
            String name = member.nameAndDesc().substring(0, paren);
            String desc = member.nameAndDesc().substring(paren);
            if (name.equals("<clinit>")) {
                continue;
            }
            boolean constructor = name.equals("<init>");
            if (constructor && isInterface) {
                continue;
            }
            hasDefaultConstructor |= constructor && desc.equals("()V");
            int methodAccess = Opcodes.ACC_PUBLIC | (member.isStatic() && !constructor ? Opcodes.ACC_STATIC : 0);
            MethodVisitor mv = cw.visitMethod(methodAccess, name, desc, null, null);
            mv.visitCode();
            if (constructor) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            }
            returnDefault(mv, Type.getReturnType(desc));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (!isInterface && !hasDefaultConstructor) {
            // Subclasses of a stubbed class call some super constructor; give them one to find.
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The real JDK class's shape when there is one; otherwise how the game uses the name. */
    private boolean isInterface(String className) {
        try {
            return Class.forName(className, false, ClassLoader.getPlatformClassLoader())
                    .isInterface();
        } catch (ClassNotFoundException | LinkageError e) {
            return index.usedAsInterface(className);
        }
    }

    private static void returnDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case Type.LONG -> {
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                mv.visitInsn(Opcodes.FCONST_0);
                mv.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
        }
    }
}
