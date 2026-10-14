package incari.classlib.substitutes.java.lang.invoke;

/**
 * {@code java.lang.invoke.MethodHandle}. No instance can exist in the browser build, since
 * {@link TMethodHandles$Lookup} finds nothing; the type is here so the lookup's signatures match
 * the JDK's. Invocations are signature-polymorphic and stay removed by the build.
 */
public abstract class TMethodHandle {

    /** Public, as TeaVM's placeholder had it. */
    public TMethodHandle() {}

    public TMethodType type() {
        throw new UnsupportedOperationException("No method handles in the browser build");
    }

    public TMethodHandle asType(TMethodType newType) {
        throw new UnsupportedOperationException("No method handles in the browser build");
    }

    public TMethodHandle bindTo(Object value) {
        throw new UnsupportedOperationException("No method handles in the browser build");
    }
}
