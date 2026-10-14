package incari.classlib.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The SLF4J provider behind the browser build's {@code LoggerFactory}: {@link ConsoleLogger}s, bound
 * directly rather than found by SLF4J's service lookup, which relies on class loading a compiled
 * page doesn't have.
 */
public final class ConsoleLogging implements SLF4JServiceProvider, ILoggerFactory {

    public static final ConsoleLogging INSTANCE = new ConsoleLogging();

    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new BasicMDCAdapter();

    private ConsoleLogging() {}

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, ConsoleLogger::new);
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return this;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {}
}
