package incari.classlib.substitutes.org.slf4j;

import incari.classlib.logging.ConsoleLogging;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * SLF4J's {@code LoggerFactory} for the browser build, bound straight to the console. The library's
 * own looks for providers with {@code ServiceLoader} and class-path scans; this skips all of that.
 */
public final class TLoggerFactory {

    public static final String PROVIDER_PROPERTY_KEY = "slf4j.provider";

    private TLoggerFactory() {}

    public static Logger getLogger(String name) {
        return ConsoleLogging.INSTANCE.getLogger(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    public static ILoggerFactory getILoggerFactory() {
        return ConsoleLogging.INSTANCE;
    }

    /** Package-private in SLF4J; {@code MarkerFactory} and {@code MDC} get their parts from it. */
    static SLF4JServiceProvider getProvider() {
        return ConsoleLogging.INSTANCE;
    }
}
