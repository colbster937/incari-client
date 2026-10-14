package incari.classlib.additions.java.lang;

import incari.classlib.InstanceMethod;

/** {@code java.lang.SecurityManager} methods TeaVM lacks. */
@SuppressWarnings("removal") // The libraries that still call it are what this serves.
public final class SecurityManagerAdditions {

    private SecurityManagerAdditions() {}

    /** The browser build can't see the call stack's classes; callers treat none as unknown. */
    @InstanceMethod
    public static Class<?>[] getClassContext(SecurityManager self) {
        return new Class<?>[0];
    }
}
