package incari.tools.survey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Scans reachable game and library bytecode for two things TeaVM itself doesn't report.
 *
 * <ul>
 *   <li><b>Reflection.</b> TeaVM compiles a closed world: it keeps only what it can see being
 *       used. A class loaded by name, or a method looked up by string, is invisible to it
 *       unless declared ahead of time. Each hit is a place to replace with a direct reference.
 *   <li><b>Host-JDK references.</b> Instructions that name a JDK class TeaVM's class library
 *       lacks (see {@link Origins}). TeaVM compiles those from the host JDK, which compiles
 *       but often can't run in a browser. Scanning the declared owner at each instruction
 *       finds exactly the game code that names such a class, with none of the call graph's
 *       virtual-dispatch noise.
 * </ul>
 */
public final class BytecodeScanner {

    /** {@code owner -> method names} of reflective entry points. An empty set means "any method". */
    private static final Map<String, Set<String>> REFLECTIVE = Map.ofEntries(
            Map.entry(
                    "java/lang/Class",
                    Set.of(
                            "forName",
                            "newInstance",
                            "getDeclaredMethod",
                            "getDeclaredMethods",
                            "getMethod",
                            "getMethods",
                            "getDeclaredField",
                            "getDeclaredFields",
                            "getField",
                            "getFields",
                            "getDeclaredConstructor",
                            "getDeclaredConstructors",
                            "getConstructor",
                            "getConstructors",
                            "getRecordComponents",
                            "getDeclaredClasses",
                            "getAnnotation",
                            "getAnnotations",
                            "getDeclaredAnnotations",
                            "getGenericSuperclass",
                            "getGenericInterfaces")),
            Map.entry(
                    "java/lang/ClassLoader",
                    Set.of(
                            "loadClass",
                            "getResource",
                            "getResources",
                            "getResourceAsStream",
                            "getSystemClassLoader",
                            "getSystemResource",
                            "getSystemResourceAsStream")),
            Map.entry("java/lang/reflect/Method", Set.of("invoke")),
            Map.entry("java/lang/reflect/Constructor", Set.of("newInstance")),
            Map.entry("java/lang/reflect/Field", Set.of()),
            Map.entry("java/lang/reflect/Proxy", Set.of("newProxyInstance")),
            Map.entry("java/lang/reflect/RecordComponent", Set.of("getAccessor")),
            Map.entry("java/util/ServiceLoader", Set.of("load", "loadInstalled")),
            Map.entry("java/lang/invoke/MethodHandles", Set.of("lookup", "publicLookup", "privateLookupIn")),
            // Unsafe isn't listed: it's a host-JDK class, reported as such at every use.
            Map.entry("java/lang/invoke/MethodHandles$Lookup", Set.of()));

    private BytecodeScanner() {}

    /**
     * Scans the given app jars, looking only inside {@code reachableMethods}
     * ({@code owner#name(descriptor)}) so that dead code TeaVM drops anyway doesn't inflate the backlog.
     */
    public static List<Finding> scan(
            Map<Path, String> jars, Set<String> reachableClasses, Set<String> reachableMethods, Origins origins)
            throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (Path jar : jars.keySet()) {
            try (JarFile file = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    if (!reachableClasses.contains(className)) {
                        continue;
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        new ClassReader(in)
                                .accept(
                                        new Visitor(className, reachableMethods, origins, findings),
                                        ClassReader.SKIP_FRAMES);
                    }
                }
            }
        }
        return findings;
    }

    private static final class Visitor extends ClassVisitor {
        private final String className;
        private final Set<String> reachableMethods;
        private final Origins origins;
        private final List<Finding> findings;

        Visitor(String className, Set<String> reachableMethods, Origins origins, List<Finding> findings) {
            super(Opcodes.ASM9);
            this.className = className;
            this.reachableMethods = reachableMethods;
            this.origins = origins;
            this.findings = findings;
        }

        /** A reachable class extending or implementing something TeaVM can't see. */
        @Override
        public void visit(
                int version,
                int access,
                String name,
                @Nullable String signature,
                @Nullable String superName,
                String @Nullable [] interfaces) {
            List<String> supertypes = new ArrayList<>();
            if (superName != null) {
                supertypes.add(superName);
            }
            if (interfaces != null) {
                supertypes.addAll(List.of(interfaces));
            }
            for (String supertype : supertypes) {
                String superClass = supertype.replace('/', '.');
                String origin = origins.of(superClass);
                if (Origins.ABSENT.equals(origin) || Origins.HOST_JDK.equals(origin)) {
                    Finding.Kind kind =
                            Origins.ABSENT.equals(origin) ? Finding.Kind.MISSING_SUPERTYPE : Finding.Kind.HOST_JDK;
                    findings.add(new Finding(
                            kind, superClass, className + " extends " + superClass, className, null, -1, null));
                }
            }
        }

        @Override
        public @Nullable MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions) {
            if (!reachableMethods.contains(className + "#" + name + descriptor)) {
                return null;
            }
            String callerMethod = name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
                private int line = -1;

                @Override
                public void visitLineNumber(int lineNumber, Label start) {
                    line = lineNumber;
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                    Set<String> names = REFLECTIVE.get(owner);
                    if (names != null && (names.isEmpty() || names.contains(method))) {
                        String symbol = owner.replace('/', '.') + "#" + method;
                        findings.add(new Finding(
                                Finding.Kind.REFLECTION, symbol, symbol + desc, className, callerMethod, line, null));
                    }
                    hostJdk(owner, method, desc);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    hostJdk(owner, field, null);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    hostJdk(type, null, null);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                        hostJdk(type.getInternalName(), null, null);
                    }
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, @Nullable String type) {
                    if (type != null) {
                        hostJdk(type, null, null);
                    }
                }

                /*
                 * Records a reference to a class that isn't compiled from the game's own jars or
                 * TeaVM's class library: either copied from the host JDK, or absent entirely
                 * (a library the rules leave out). TeaVM names an absent class only once, at its
                 * first use; this finds every call site.
                 */
                private void hostJdk(String owner, @Nullable String member, @Nullable String desc) {
                    if (owner.startsWith("[")) {
                        return;
                    }
                    String ownerName = owner.replace('/', '.');
                    String origin = origins.of(ownerName);
                    Finding.@Nullable Kind kind =
                            switch (origin) {
                                case Origins.HOST_JDK -> Finding.Kind.HOST_JDK;
                                case Origins.ABSENT -> Finding.Kind.MISSING_CLASS;
                                default -> null;
                            };
                    if (kind == null) {
                        return;
                    }
                    String symbol = member != null ? ownerName + "#" + member : ownerName;
                    String detail = desc != null ? symbol + desc : symbol;
                    findings.add(new Finding(kind, symbol, detail, className, callerMethod, line, null));
                }
            };
        }
    }
}
