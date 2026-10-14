package incari.tools.webbuild;

import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.emit.ValueEmitter;

/**
 * Adds methods a class TeaVM has lacks, from {@code web-classlib}'s additions classes: the
 * public static methods of {@code incari.classlib.additions.<package>.<Name>Additions} become
 * methods of {@code <package>.<Name>}, each forwarding to its addition.
 *
 * <p>An addition annotated {@code @incari.classlib.InstanceMethod} becomes an instance method:
 * its first parameter is the receiver ({@code static long threadId(Thread self)} adds
 * {@code Thread.threadId()}). Others are added as static methods. A method the class already has
 * is left alone, so an addition goes quiet once TeaVM catches up, unless TeaVM marks it
 * {@code @UnsupportedOn} a platform or the addition is marked {@code @incari.classlib.Replaces}:
 * then the addition replaces it. An addition marked {@code @incari.classlib.Constructor} becomes a
 * constructor with its parameters that hands its result to the class's own constructor for that
 * type.
 */
public final class AdditionsTransformer implements ClassHolderTransformer {

    static final String PACKAGE = "incari.classlib.additions";
    static final String INSTANCE_METHOD = "incari.classlib.InstanceMethod";
    static final String REPLACES = "incari.classlib.Replaces";
    static final String CONSTRUCTOR = "incari.classlib.Constructor";
    private static final String UNSUPPORTED_ON = "org.teavm.interop.UnsupportedOn";

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (cls.getName().startsWith("incari.")) {
            return;
        }
        ClassHierarchy hierarchy = context.getHierarchy();
        ClassReader additions = hierarchy.getClassSource().get(PACKAGE + "." + cls.getName() + "Additions");
        if (additions == null) {
            return;
        }
        for (MethodReader addition : additions.getMethods()) {
            if (!addition.hasModifier(ElementModifier.STATIC)
                    || addition.getLevel() != AccessLevel.PUBLIC
                    || addition.hasModifier(ElementModifier.SYNTHETIC)) {
                continue;
            }
            boolean instance = addition.getAnnotations().get(INSTANCE_METHOD) != null;
            // The hierarchy reads classes as they were before this compile's transformers ran, so
            // substitute names in an addition's signature are mapped here, as ClasslibRenamer maps
            // them in the additions class itself.
            MethodDescriptor original = originalNames(addition.getDescriptor());
            MethodReference target = new MethodReference(addition.getOwnerName(), original);
            if (addition.getAnnotations().get(CONSTRUCTOR) != null) {
                MethodDescriptor constructor = asConstructor(original);
                if (cls.getMethod(constructor) == null) {
                    cls.addMethod(delegatingConstructor(cls, constructor, original.getResultType(), target, hierarchy));
                }
                continue;
            }
            MethodDescriptor descriptor = instance ? dropReceiver(original) : original;
            MethodHolder existing = cls.getMethod(descriptor);
            if (existing != null) {
                // TeaVM marks methods it can't provide on a target; an addition stands in for those,
                // and an addition marked @Replaces takes the place of TeaVM's version.
                if (existing.getAnnotations().get(UNSUPPORTED_ON) == null
                        && addition.getAnnotations().get(REPLACES) == null) {
                    continue;
                }
                cls.removeMethod(existing);
            }
            cls.addMethod(forwarder(cls, descriptor, instance, target, hierarchy));
        }
    }

    /** {@code descriptor} with substitute ({@code T}) class names read as the JDK names they stand for. */
    static MethodDescriptor originalNames(MethodDescriptor descriptor) {
        ValueType[] signature = descriptor.getSignature();
        for (int i = 0; i < signature.length; i++) {
            signature[i] = originalName(signature[i]);
        }
        return new MethodDescriptor(descriptor.getName(), signature);
    }

    private static ValueType originalName(ValueType type) {
        if (type instanceof ValueType.Array array) {
            return ValueType.arrayOf(originalName(array.getItemType()));
        }
        if (type instanceof ValueType.Object object) {
            return ValueType.object(ClasslibRenamer.original(object.getClassName()));
        }
        return type;
    }

    /** The constructor an addition marked {@code @Constructor} adds: its parameters, returning nothing. */
    static MethodDescriptor asConstructor(MethodDescriptor descriptor) {
        ValueType[] constructor = new ValueType[descriptor.parameterCount() + 1];
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            constructor[i] = descriptor.parameterType(i);
        }
        constructor[constructor.length - 1] = ValueType.VOID;
        return new MethodDescriptor("<init>", constructor);
    }

    static MethodDescriptor dropReceiver(MethodDescriptor descriptor) {
        ValueType[] signature = descriptor.getSignature();
        ValueType[] rest = new ValueType[signature.length - 1];
        System.arraycopy(signature, 1, rest, 0, rest.length);
        return new MethodDescriptor(descriptor.getName(), rest);
    }

    /** {@code Name(parameters) { this(addition(parameters)); }} */
    private static MethodHolder delegatingConstructor(
            ClassHolder cls,
            MethodDescriptor descriptor,
            ValueType argument,
            MethodReference target,
            ClassHierarchy hierarchy) {
        MethodHolder method = new MethodHolder(descriptor);
        method.setLevel(AccessLevel.PUBLIC);
        ProgramEmitter pe = ProgramEmitter.create(method, hierarchy);
        ValueEmitter self = pe.var(0, ValueType.object(cls.getName()));
        ValueEmitter[] arguments = new ValueEmitter[descriptor.parameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = pe.var(i + 1, descriptor.parameterType(i));
        }
        ValueEmitter value = pe.invoke(target, arguments);
        self.invokeSpecial(new MethodReference(cls.getName(), "<init>", argument, ValueType.VOID), value);
        pe.exit();
        return method;
    }

    private static MethodHolder forwarder(
            ClassHolder cls,
            MethodDescriptor descriptor,
            boolean instance,
            MethodReference target,
            ClassHierarchy hierarchy) {
        MethodHolder method = new MethodHolder(descriptor);
        method.setLevel(AccessLevel.PUBLIC);
        if (!instance) {
            method.getModifiers().add(ElementModifier.STATIC);
        }
        ProgramEmitter pe = ProgramEmitter.create(method, hierarchy);
        int offset = instance ? 1 : 0;
        ValueEmitter[] arguments = new ValueEmitter[descriptor.parameterCount() + offset];
        if (instance) {
            arguments[0] = pe.var(0, ValueType.object(cls.getName()));
        }
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            arguments[i + offset] = pe.var(i + 1, descriptor.parameterType(i));
        }
        ValueEmitter result = pe.invoke(target, arguments);
        if (descriptor.getResultType() == ValueType.VOID) {
            pe.exit();
        } else {
            result.returnValue();
        }
        return method;
    }
}
