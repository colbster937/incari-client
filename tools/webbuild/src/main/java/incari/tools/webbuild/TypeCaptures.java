package incari.tools.webbuild;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.Signature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.emit.ValueEmitter;

/**
 * Keeps the generic superclass of "type capture" classes for {@code Class.getGenericSuperclass}:
 * the anonymous {@code new TypeToken<List<String>>() {}} subclasses of Guava's and Gson's type
 * tokens (and of the copy of Gson that Via bundles), which read their type argument from their superclass. TeaVM keeps no generic
 * signatures, and DataFixerUpper and the game build such tokens while starting.
 *
 * <p>Netty's handlers read their superclass's type argument the same way
 * ({@code TypeParameterMatcher}, through {@code ReflectionUtil.resolveTypeParameter}) to decide
 * which messages they handle, and without it every handler matches every message: the game's
 * {@code PacketEncoder} was handed its pipeline's configuration messages and failed with a
 * {@code ClassCastException}. Netty only needs the argument's raw class, so for direct
 * subclasses of {@link #NETTY_BASES} an argument that names a type variable inside it
 * ({@code MessageToByteEncoder<Packet<T>>}) is kept as its raw class ({@code Packet}).
 *
 * <p>{@link Main} scans the class path for direct subclasses of {@link #BASES} and
 * {@link #NETTY_BASES} ({@link #scan});
 * this transformer then writes {@code incari.classlib.reflect.GenericSuperclasses.of} as a
 * lookup: each class's superclass signature in {@code GenericTypes}' compact form, with the
 * classes it names as class literals. A signature with a type variable isn't kept (nothing in
 * the build needs one yet), so {@code getGenericSuperclass} falls back to the raw class there.
 *
 * <p>TeaVM instantiates transformers by class name, so {@link Main} hands the scan over through
 * {@link #captures}. One compile runs at a time.
 */
public final class TypeCaptures implements ClassHolderTransformer {

    /** Classes whose subclasses capture their type argument. */
    static final Set<String> BASES = Set.of(
            "com/google/common/reflect/TypeCapture",
            "com/google/common/reflect/TypeToken",
            "com/google/common/reflect/TypeParameter",
            "com/google/gson/reflect/TypeToken",
            // Via's own copy of Gson: its 1.13 translation reads JSON through one.
            "com/viaversion/viaversion/libs/gson/reflect/TypeToken");

    /** Netty's classes that match messages (or addresses) by their subclass's type argument. */
    static final Set<String> NETTY_BASES = Set.of(
            "io/netty/channel/SimpleChannelInboundHandler",
            "io/netty/channel/SimpleUserEventChannelHandler",
            "io/netty/handler/codec/ByteToMessageCodec",
            "io/netty/handler/codec/MessageAggregator",
            "io/netty/handler/codec/MessageToByteEncoder",
            "io/netty/handler/codec/MessageToMessageCodec",
            "io/netty/handler/codec/MessageToMessageDecoder",
            "io/netty/handler/codec/MessageToMessageEncoder",
            "io/netty/resolver/AbstractAddressResolver");

    static volatile Map<String, Capture> captures = Map.of();

    private static final String LOOKUP = "incari.classlib.reflect.GenericSuperclasses";
    private static final MethodDescriptor OF =
            new MethodDescriptor("of", ValueType.object("java.lang.Class"), ValueType.object("java.lang.reflect.Type"));
    private static final MethodReference PARSE = new MethodReference(
            "incari.classlib.reflect.GenericTypes",
            "parse",
            ValueType.object("java.lang.String"),
            ValueType.arrayOf(ValueType.object("java.lang.Class")),
            ValueType.object("java.lang.reflect.Type"));

    /** A superclass in {@code GenericTypes}' form: its code, and the classes it names in order. */
    record Capture(String code, List<ValueType> classes) {}

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (!cls.getName().equals(LOOKUP)) {
            return;
        }
        MethodHolder method = cls.getMethod(OF);
        if (method == null) {
            throw new IllegalStateException(LOOKUP + " has no " + OF);
        }
        ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
        ValueEmitter requested = pe.var(1, ValueType.object("java.lang.Class"));
        ClassReaderSource source = context.getHierarchy().getClassSource();
        // The scan reads every jar on the class path, including libraries the build leaves out
        // (Netty's HTTP codec): a class literal of one of those wouldn't link.
        Map<String, Capture> kept = new TreeMap<>(captures);
        kept.entrySet()
                .removeIf(entry -> !exists(source, ValueType.object(entry.getKey()))
                        || !entry.getValue().classes().stream().allMatch(type -> exists(source, type)));
        kept.forEach((className, capture) -> pe.when(requested.isSame(pe.constant(ValueType.object(className))))
                .thenDo(() -> {
                    ValueEmitter classes = pe.constructArray(
                            ValueType.object("java.lang.Class"),
                            capture.classes().size());
                    for (int i = 0; i < capture.classes().size(); i++) {
                        classes.setElement(i, pe.constant(capture.classes().get(i)));
                    }
                    pe.invoke(PARSE, pe.constant(capture.code()), classes).returnValue();
                }));
        pe.constantNull(ValueType.object("java.lang.reflect.Type")).returnValue();
    }

    private static boolean exists(ClassReaderSource classes, ValueType type) {
        ValueType item = type;
        while (item instanceof ValueType.Array array) {
            item = array.getItemType();
        }
        return !(item instanceof ValueType.Object object) || classes.get(object.getClassName()) != null;
    }

    /** The type-capture classes on the class path, by binary name. */
    static Map<String, Capture> scan(List<Path> classpath) throws IOException {
        Map<String, Capture> found = new TreeMap<>();
        for (Path entry : classpath) {
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    for (JarEntry file : (Iterable<JarEntry>) jar.stream()::iterator) {
                        if (file.getName().endsWith(".class")) {
                            consider(jar.getInputStream(file).readAllBytes(), found);
                        }
                    }
                }
            } else if (Files.isDirectory(entry)) {
                try (Stream<Path> files = Files.walk(entry)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (file.toString().endsWith(".class")) {
                            consider(Files.readAllBytes(file), found);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static void consider(byte[] bytes, Map<String, Capture> found) {
        ClassModel model = ClassFile.of().parse(bytes);
        if (model.superclass().isEmpty()) {
            return;
        }
        String superclass = model.superclass().get().asInternalName();
        boolean netty = NETTY_BASES.contains(superclass);
        if (!netty && !BASES.contains(superclass)) {
            return;
        }
        model.findAttribute(Attributes.signature()).ifPresent(signature -> {
            Signature.ClassTypeSig superclassSignature = ClassSignature.parseFrom(
                            signature.signature().stringValue())
                    .superclassSignature();
            Capture capture = encode(netty ? rawArguments(superclassSignature) : superclassSignature);
            if (capture != null) {
                found.put(model.thisClass().asInternalName().replace('/', '.'), capture);
            }
        });
    }

    /**
     * {@code signature} with each type argument that is a parameterized class reduced to its raw
     * class, which is all Netty reads ({@code Foo<Packet<T>>} becomes {@code Foo<Packet>}). A
     * type variable itself stays one, so such a signature still isn't kept.
     */
    static Signature.ClassTypeSig rawArguments(Signature.ClassTypeSig signature) {
        List<Signature.TypeArg> arguments = new ArrayList<>();
        for (Signature.TypeArg argument : signature.typeArgs()) {
            if (argument instanceof Signature.TypeArg.Bounded bounded
                    && bounded.wildcardIndicator() == Signature.TypeArg.Bounded.WildcardIndicator.NONE
                    && bounded.boundType() instanceof Signature.ClassTypeSig type
                    && !type.typeArgs().isEmpty()) {
                arguments.add(Signature.TypeArg.of(Signature.ClassTypeSig.of(type.classDesc())));
            } else {
                arguments.add(argument);
            }
        }
        return signature.outerType().isPresent()
                ? Signature.ClassTypeSig.of(
                        signature.outerType().get(), signature.className(), arguments.toArray(Signature.TypeArg[]::new))
                : Signature.ClassTypeSig.of(signature.classDesc(), arguments.toArray(Signature.TypeArg[]::new));
    }

    /** {@code signature} in {@code GenericTypes}' form, or null if it has a type variable. */
    static @Nullable Capture encode(Signature signature) {
        StringBuilder code = new StringBuilder();
        List<ValueType> classes = new ArrayList<>();
        return encode(signature, code, classes) ? new Capture(code.toString(), List.copyOf(classes)) : null;
    }

    private static boolean encode(Signature signature, StringBuilder code, List<ValueType> classes) {
        return switch (signature) {
            case Signature.ClassTypeSig type -> encodeClass(type, code, classes);
            case Signature.ArrayTypeSig array -> encodeArray(array, code, classes);
            case Signature.BaseTypeSig base -> {
                // Only as an array's component, which encodeArray folds into one class.
                code.append('C');
                classes.add(ValueType.parse(String.valueOf(base.baseType())));
                yield true;
            }
            case Signature.TypeVarSig variable -> false;
        };
    }

    private static boolean encodeClass(Signature.ClassTypeSig type, StringBuilder code, List<ValueType> classes) {
        String name = type.classDesc().descriptorString();
        ValueType self = ValueType.parse(name);
        boolean genericOwner = type.outerType().isPresent()
                && !type.outerType().get().typeArgs().isEmpty();
        if (type.typeArgs().isEmpty() && !genericOwner) {
            code.append('C');
            classes.add(self);
            return true;
        }
        if (type.typeArgs().size() > 9) {
            return false;
        }
        code.append('P');
        classes.add(self);
        // The owner, as the JDK reports it: the enclosing type for a nested class, else none.
        String binaryName = ((ValueType.Object) self).getClassName();
        if (type.outerType().isPresent()) {
            if (!encode(type.outerType().get(), code, classes)) {
                return false;
            }
        } else if (binaryName.lastIndexOf('$') > binaryName.lastIndexOf('.')) {
            code.append('C');
            classes.add(ValueType.object(binaryName.substring(0, binaryName.lastIndexOf('$'))));
        } else {
            code.append('N');
        }
        code.append(type.typeArgs().size());
        for (Signature.TypeArg argument : type.typeArgs()) {
            boolean encoded =
                    switch (argument) {
                        case Signature.TypeArg.Unbounded unbounded -> {
                            code.append("UC");
                            classes.add(ValueType.object("java.lang.Object"));
                            yield true;
                        }
                        case Signature.TypeArg.Bounded bounded -> {
                            switch (bounded.wildcardIndicator()) {
                                case EXTENDS -> code.append('U');
                                case SUPER -> code.append('L');
                                case NONE -> {}
                            }
                            yield encode(bounded.boundType(), code, classes);
                        }
                    };
            if (!encoded) {
                return false;
            }
        }
        return true;
    }

    private static boolean encodeArray(Signature.ArrayTypeSig array, StringBuilder code, List<ValueType> classes) {
        StringBuilder component = new StringBuilder();
        List<ValueType> componentClasses = new ArrayList<>();
        if (!encode(array.componentSignature(), component, componentClasses)) {
            return false;
        }
        if (component.toString().equals("C")) {
            // An array of a plain class is a class itself.
            code.append('C');
            classes.add(ValueType.arrayOf(componentClasses.get(0)));
        } else {
            code.append('A').append(component);
            classes.addAll(componentClasses);
        }
        return true;
    }
}
