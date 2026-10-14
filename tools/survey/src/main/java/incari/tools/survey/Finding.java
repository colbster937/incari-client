package incari.tools.survey;

import org.jspecify.annotations.Nullable;

/**
 * One place where the client (or a library it keeps) uses something the browser build
 * can't provide as-is.
 *
 * @param kind      what went wrong, e.g. a missing method or a reflective call
 * @param symbol    the thing that's missing or used: a class ({@code java.nio.file.Files}),
 *                  method ({@code java.nio.file.Files#walk}) or field
 * @param detail    the full descriptor, for the machine-readable report
 * @param caller    the calling class, or {@code null} when TeaVM gives no location
 * @param callerMethod the calling method's name and descriptor, or {@code null}
 * @param line      source line in the caller, or -1
 * @param via       for findings TeaVM reported inside JDK or class-library code, the method
 *                  there that the game's {@code caller} reached it through; otherwise {@code null}
 */
public record Finding(
        Kind kind,
        String symbol,
        String detail,
        @Nullable String caller,
        @Nullable String callerMethod,
        int line,
        @Nullable String via) {

    public enum Kind {
        MISSING_CLASS("missing class"),
        MISSING_METHOD("missing method"),
        MISSING_FIELD("missing field"),
        UNSUPPORTED_CLASS("unsupported on target"),
        UNSUPPORTED_METHOD("unsupported on target"),
        MISSING_SUPERTYPE("missing supertype"),
        MISSING_BOOTSTRAP("unsupported invokedynamic"),
        NATIVE("native method"),
        HOST_JDK("JDK class TeaVM lacks"),
        REFLECTION("reflection"),
        OTHER("other compiler error");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The name rules.txt uses: {@code MISSING_METHOD} -> {@code missing-method}. */
        public String ruleName() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /** The class that owns {@link #symbol}: {@code java.nio.file.Files#walk} → {@code java.nio.file.Files}. */
    public String owner() {
        int hash = symbol.indexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    /** The member part of {@link #symbol}, or {@code null} for a class. */
    public @Nullable String member() {
        int hash = symbol.indexOf('#');
        return hash < 0 ? null : symbol.substring(hash + 1);
    }
}
