package incari.classlib.substitutes.java.lang;

import incari.classlib.UnknownCaller;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code java.lang.StackWalker}, which TeaVM lacks. The browser build can't see its own call stack
 * as classes and methods, so every walk sees no frames and the caller class is
 * {@link UnknownCaller}.
 */
public final class TStackWalker {

    private static final TStackWalker DEFAULT = new TStackWalker(EnumSet.noneOf(Option.class));

    private final Set<Option> options;

    private TStackWalker(Set<Option> options) {
        this.options = options;
    }

    public static TStackWalker getInstance() {
        return DEFAULT;
    }

    public static TStackWalker getInstance(Option option) {
        return new TStackWalker(EnumSet.of(option));
    }

    public static TStackWalker getInstance(Set<Option> options) {
        return new TStackWalker(options.isEmpty() ? EnumSet.noneOf(Option.class) : EnumSet.copyOf(options));
    }

    public static TStackWalker getInstance(Set<Option> options, int estimateDepth) {
        if (estimateDepth <= 0) {
            throw new IllegalArgumentException("estimateDepth must be > 0");
        }
        return getInstance(options);
    }

    public <T> T walk(Function<? super Stream<StackFrame>, ? extends T> function) {
        return function.apply(Stream.empty());
    }

    public void forEach(Consumer<? super StackFrame> action) {
        // No frames to visit.
    }

    public Class<?> getCallerClass() {
        if (!options.contains(Option.RETAIN_CLASS_REFERENCE)) {
            throw new UnsupportedOperationException("This stack walker does not have RETAIN_CLASS_REFERENCE access");
        }
        return UnknownCaller.class;
    }

    Set<Option> options() {
        return Collections.unmodifiableSet(options);
    }

    /** What a walk shows and keeps. */
    public enum Option {
        RETAIN_CLASS_REFERENCE,
        SHOW_REFLECT_FRAMES,
        SHOW_HIDDEN_FRAMES,
        DROP_METHOD_INFO
    }

    /** One frame of a walk; none are ever produced here. */
    public interface StackFrame {
        String getClassName();

        String getMethodName();

        Class<?> getDeclaringClass();

        default MethodType getMethodType() {
            throw new UnsupportedOperationException();
        }

        default String getDescriptor() {
            throw new UnsupportedOperationException();
        }

        int getByteCodeIndex();

        String getFileName();

        int getLineNumber();

        boolean isNativeMethod();

        StackTraceElement toStackTraceElement();
    }
}
