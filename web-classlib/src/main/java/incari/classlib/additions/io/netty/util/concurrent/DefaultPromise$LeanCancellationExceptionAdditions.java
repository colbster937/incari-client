package incari.classlib.additions.io.netty.util.concurrent;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;

/**
 * Netty's cancellation exception for promises, {@code DefaultPromise.LeanCancellationException},
 * with a {@code fillInStackTrace} that leaves the stack empty. The class name carries the
 * nested class's binary name, as the additions transformer looks it up.
 *
 * <p>Netty's version sets a shared one-frame stack held in a {@code DefaultPromise} static. That
 * one read keeps every exception constructor in the browser build from being plain code:
 * {@code Throwable}'s constructors call {@code fillInStackTrace} virtually, TeaVM follows the call
 * to this override, then into {@code DefaultPromise}'s class initializer, which makes loggers,
 * signals and system property lookups through hot map calls that can suspend. So every exception
 * constructor became a coroutine, and with them {@code String.charAt} and the JavaScript interop's
 * string conversion, which the page calls from JavaScript while it loads: the module crashed
 * ("dereferencing a null pointer" in {@code Fiber.isResuming}). The exception is only made inside
 * {@code DefaultPromise.cancel}, so its stack is never needed to find anything.
 */
public final class DefaultPromise$LeanCancellationExceptionAdditions {

    private DefaultPromise$LeanCancellationExceptionAdditions() {}

    /** Replaces Netty's override, which reads {@code DefaultPromise}'s shared stack. */
    @Replaces
    @InstanceMethod
    public static Throwable fillInStackTrace(Throwable self) {
        return self;
    }
}
