package incari.classlib.additions.io.netty.util.internal.logging;

import incari.classlib.Replaces;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

/**
 * Netty's logger lookup, bound to SLF4J (which the browser build binds to the console) instead of
 * probing for Log4j 2 first.
 *
 * <p>Not only tidier: Netty's first logger is made in {@code DefaultPromise}'s static initializer,
 * which one of its exceptions' {@code fillInStackTrace} triggers. Log4j's provider lookup takes a
 * lock, so with it reachable, TeaVM compiled every exception constructor, and with them
 * {@code String.charAt} and the JavaScript interop's string conversion, as coroutines, and the page
 * crashed while loading. SLF4J's binding takes no lock.
 */
public final class InternalLoggerFactoryAdditions {

    private InternalLoggerFactoryAdditions() {}

    /** Replaces Netty's private probe for a logging library. */
    @Replaces
    @SuppressWarnings("deprecation") // INSTANCE skips the NOP-binding check, which is moot here.
    public static InternalLoggerFactory newDefaultFactory(String name) {
        return Slf4JLoggerFactory.INSTANCE;
    }
}
