package incari.tools.webbuild;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;

/**
 * Routes TeaVM's fibers onto JSPI (WebAssembly JavaScript Promise Integration) for a {@code --jspi}
 * build: {@code Fiber.suspend} and {@code Fiber.start} call {@code Fiber.Jspi} (tools/teavm-patches)
 * instead. With the system property {@code incari.jspi} also set, TeaVM compiles no method as a
 * coroutine; every suspension goes through {@code Fiber.suspend}, which then hands the engine a
 * promise and keeps the whole stack. Only this routing makes {@code Fiber.Jspi} reachable, so other
 * builds don't import its JavaScript functions.
 */
public final class JspiTransformer implements ClassHolderTransformer {

    /** The system property TeaVM's patched {@code AsyncMethodFinder} reads. */
    static final String PROPERTY = "incari.jspi";

    private static final String FIBER = "org.teavm.runtime.Fiber";
    private static final String JSPI = FIBER + "$Jspi";
    private static final ValueType ASYNC_CALL = ValueType.object(FIBER + "$AsyncCall");
    private static final ValueType RUNNER = ValueType.object(FIBER + "$FiberRunner");
    private static final ValueType OBJECT = ValueType.object("java.lang.Object");

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (!cls.getName().equals(FIBER)) {
            return;
        }
        MethodHolder suspend = method(cls, new MethodDescriptor("suspend", ASYNC_CALL, OBJECT));
        ProgramEmitter pe = ProgramEmitter.create(suspend, context.getHierarchy());
        pe.invoke(JSPI, "suspend", OBJECT, pe.var(1, ASYNC_CALL)).returnValue();

        MethodHolder start = method(cls, new MethodDescriptor("start", RUNNER, ValueType.BOOLEAN, ValueType.VOID));
        pe = ProgramEmitter.create(start, context.getHierarchy());
        pe.invoke(JSPI, "start", pe.var(1, RUNNER), pe.var(2, ValueType.BOOLEAN));
        pe.exit();
    }

    private static MethodHolder method(ClassHolder cls, MethodDescriptor descriptor) {
        MethodHolder method = cls.getMethod(descriptor);
        if (method == null) {
            throw new IllegalStateException("TeaVM's Fiber has no " + descriptor + " to route onto JSPI");
        }
        return method;
    }
}
