package incari.classlib;

/**
 * What code the browser build strips turns into. The web build's link step replaces each
 * reference to a removed class or member (a desktop-only library, a native API) with a call to
 * {@link #reference}, so reaching it at run time fails loudly instead of the compile failing.
 *
 * <p>The web build refers to this class by name; keep its name and members stable.
 */
public final class Removed {

    private Removed() {}

    /**
     * Always throws: the calling code reached something the browser build doesn't include.
     *
     * @param symbol the class or member, as {@code pkg.Class} or {@code pkg.Class#member}
     */
    public static void reference(String symbol) {
        throw new UnsupportedOperationException(symbol + " isn't part of the browser build");
    }

    /**
     * Stands in for a removed class in class literals ({@code InetAddress.class}): nothing is an
     * instance of it, so type checks against it answer no.
     */
    public static final class Absent {
        private Absent() {}
    }

    /**
     * Stands in for an exception type the build removed, in {@code catch} clauses: nothing can
     * throw one, so the handler never runs.
     */
    public static final class Never extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Never() {}
    }
}
