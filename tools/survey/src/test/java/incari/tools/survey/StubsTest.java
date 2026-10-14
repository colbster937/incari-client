package incari.tools.survey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;

/** Stub generation for absent classes, checked by loading the generated classes on the real JVM. */
class StubsTest {

    @TempDir
    Path dir;

    @Test
    void absentClassStubsCarryEveryReferencedMemberAndLoad() throws Exception {
        // A "game" class that uses an absent library class three ways.
        Path jar = jarWith("game/User", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "lib/Gl", "glDraw", "(II)I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "lib/Gl", "GL_TRIANGLES", "I");
            mv.visitInsn(Opcodes.POP);
            mv.visitTypeInsn(Opcodes.NEW, "lib/Window");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("title");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "lib/Window", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "lib/Window", "title", "()Ljava/lang/String;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });

        Stubs stubs = new Stubs(ReferenceIndex.build(java.util.List.of(jar)));
        assertThat(stubs.addAbsentClass("lib.Gl")).isTrue();
        assertThat(stubs.addAbsentClass("lib.Gl")).isFalse();
        stubs.addAbsentClass("lib.Window");
        // A member TeaVM reports later, which the game's own bytecode never showed.
        stubs.addMethod(new MethodReference("lib.Window", MethodDescriptor.parse("close()V")), true);
        stubs.writeClasses(dir);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            Class<?> gl = loader.loadClass("lib.Gl");
            Method draw = gl.getMethod("glDraw", int.class, int.class);
            assertThat(Modifier.isStatic(draw.getModifiers())).isTrue();
            assertThat(draw.invoke(null, 1, 2)).isEqualTo(0);
            assertThat(Modifier.isStatic(gl.getField("GL_TRIANGLES").getModifiers()))
                    .isTrue();

            Class<?> window = loader.loadClass("lib.Window");
            Object instance = window.getConstructor(String.class).newInstance("x");
            assertThat(window.getMethod("title").invoke(instance)).isNull();
            window.getMethod("close").invoke(instance);
            // Always a no-arg constructor, for subclasses whose super call the index missed.
            assertThat(window.getConstructor()).isNotNull();
        }
    }

    @Test
    void missingMembersOfExistingClassesGoToTheTransformerWithJdkStaticness() {
        Stubs stubs = new Stubs(emptyIndex());
        stubs.addMethod(new MethodReference("java.lang.System", MethodDescriptor.parse("exit(I)V")), false);
        stubs.addMethod(
                new MethodReference(
                        "java.lang.Runtime", MethodDescriptor.parse("addShutdownHook(Ljava/lang/Thread;)V")),
                false);

        assertThat(stubs.extraMembers().get("java.lang.System"))
                .containsExactly(new Stubs.ExtraMember("exit(I)V", true, true));
        assertThat(stubs.extraMembers().get("java.lang.Runtime"))
                .containsExactly(new Stubs.ExtraMember("addShutdownHook(Ljava/lang/Thread;)V", true, false));
        assertThat(stubs.absentClassCount()).isZero();
    }

    private ReferenceIndex emptyIndex() {
        try {
            return ReferenceIndex.build(java.util.List.of());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path jarWith(String internalName, java.util.function.Consumer<ClassWriter> body) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        Path jar = dir.resolve("game.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(internalName + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        return jar;
    }
}
