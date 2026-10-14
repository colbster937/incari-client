package incari.classlib.substitutes.java.lang.invoke;

/**
 * {@code java.lang.invoke.MethodHandles}: a lookup exists, but nothing can be found through it.
 * TeaVM compiles a closed world with no method handles at run time, and its own class is an empty
 * placeholder, so a library's first {@code MethodHandles.lookup()} failed outright. Libraries that
 * probe for faster paths (Netty's {@code PlatformDependent0} calls {@code lookup()} before any of
 * its guarded probes) are written for platforms where a lookup finds nothing: every {@code find}
 * and {@code unreflect} throws the checked exception the JDK documents, and they fall back as they
 * would there.
 */
public class TMethodHandles {

    /** Public, as TeaVM's placeholder had it. */
    public TMethodHandles() {}

    public static TMethodHandles$Lookup lookup() {
        return TMethodHandles$Lookup.NOTHING;
    }

    public static TMethodHandles$Lookup publicLookup() {
        return TMethodHandles$Lookup.NOTHING;
    }

    @SuppressWarnings("unused") // The parameters exist to match the JDK signature.
    public static TMethodHandles$Lookup privateLookupIn(Class<?> target, TMethodHandles$Lookup caller)
            throws IllegalAccessException {
        throw new IllegalAccessException("Method handles aren't available in the browser build");
    }
}
