package incari.tools.survey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class RootsTest {

    @TempDir
    Path dir;

    @Test
    void everyMethodOfEveryRootedClassIsCalledAndExcludedClassesAreNot() throws Exception {
        Path jar = dir.resolve("client.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            add(out, "game/Screen", Opcodes.ACC_PUBLIC, cw -> {
                method(cw, Opcodes.ACC_PUBLIC, "<init>", "()V");
                method(cw, Opcodes.ACC_PUBLIC, "render", "(IF)V");
                method(cw, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "helper", "(J)Ljava/lang/String;");
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "skipped", "()V", null, null)
                        .visitEnd();
            });
            add(
                    out,
                    "game/data/Generator",
                    Opcodes.ACC_PUBLIC,
                    cw -> method(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V"));
        }

        int rooted = Roots.generate(jar, name -> !name.startsWith("game.data."), "game.Main", dir.resolve("roots"));

        assertThat(rooted).isEqualTo(1);
        List<String> calls = calls(dir.resolve("roots/incari/survey/roots/Roots0.class"));
        assertThat(calls)
                .contains("game/Screen.<init>", "game/Screen.render", "game/Screen.helper")
                .doesNotContain("game/Screen.skipped", "game/data/Generator.run");
        assertThat(calls(dir.resolve("roots/incari/survey/roots/Entry.class")))
                .containsExactly("game/Main.main", "incari/survey/roots/Roots0.run");
    }

    private static List<String> calls(Path classFile) throws IOException {
        List<String> calls = new ArrayList<>();
        new ClassReader(Files.readAllBytes(classFile))
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String method, String d, boolean itf) {
                                        if (!owner.startsWith("incari/survey/roots/Roots") || method.equals("run")) {
                                            calls.add(owner + "." + method);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }

    private static void add(JarOutputStream out, String name, int access, java.util.function.Consumer<ClassWriter> body)
            throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(cw.toByteArray());
        out.closeEntry();
    }

    private static void method(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
