package incari.tools.survey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * How the game's bytecode uses classes it doesn't ship: which members it touches, whether
 * each is static, and which types it treats as interfaces.
 *
 * <p>The auto-stubber needs this to fake a missing class convincingly enough for TeaVM's
 * analysis to continue. TeaVM's own error says only "method X not found", not whether X is
 * static or lives on an interface.
 */
final class ReferenceIndex {

    /** A member reference as seen at a call site. {@code nameAndDesc} is e.g. {@code "foo(I)V"} or {@code "bar:I"}. */
    record Member(String owner, String nameAndDesc, boolean method, boolean isStatic) {}

    private final Map<String, Map<String, Member>> byOwner = new HashMap<>();
    private final Set<String> interfaces = new HashSet<>();
    private final Set<String> superclasses = new HashSet<>();

    static ReferenceIndex build(Iterable<Path> jars) throws IOException {
        ReferenceIndex index = new ReferenceIndex();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                        continue;
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        new ClassReader(in)
                                .accept(index.new Visitor(), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    }
                }
            }
        }
        return index;
    }

    /** Every member of {@code owner} (dotted name) the game references. */
    Iterable<Member> membersOf(String owner) {
        Map<String, Member> members = byOwner.get(owner);
        return members != null ? members.values() : Set.of();
    }

    /** The recorded static-ness of a member, or {@code null} if the game never references it. */
    @Nullable
    Boolean isStatic(String owner, String nameAndDesc) {
        Map<String, Member> members = byOwner.get(owner);
        Member member = members != null ? members.get(nameAndDesc) : null;
        return member != null ? member.isStatic() : null;
    }

    boolean usedAsInterface(String owner) {
        return interfaces.contains(owner) && !superclasses.contains(owner);
    }

    private void add(String internalOwner, String nameAndDesc, boolean method, boolean isStatic) {
        String owner = internalOwner.replace('/', '.');
        byOwner.computeIfAbsent(owner, k -> new HashMap<>())
                .putIfAbsent(nameAndDesc, new Member(owner, nameAndDesc, method, isStatic));
    }

    private final class Visitor extends ClassVisitor {
        Visitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                @Nullable String signature,
                @Nullable String superName,
                String @Nullable [] ifaces) {
            if (superName != null) {
                superclasses.add(superName.replace('/', '.'));
            }
            if (ifaces != null) {
                for (String iface : ifaces) {
                    interfaces.add(iface.replace('/', '.'));
                }
            }
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                    if (owner.startsWith("[")) {
                        return;
                    }
                    add(owner, method + desc, true, opcode == Opcodes.INVOKESTATIC);
                    if (isInterface) {
                        interfaces.add(owner.replace('/', '.'));
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    add(owner, field + ":" + desc, false, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);
                }
            };
        }
    }
}
