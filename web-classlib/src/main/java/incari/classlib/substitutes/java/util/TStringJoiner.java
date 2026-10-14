package incari.classlib.substitutes.java.util;

import org.jspecify.annotations.Nullable;

/** {@code java.util.StringJoiner}, which TeaVM lacks. */
public final class TStringJoiner {

    private final String prefix;
    private final String delimiter;
    private final String suffix;
    private @Nullable StringBuilder joined;
    private @Nullable String emptyValue;

    public TStringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public TStringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        this.delimiter = delimiter.toString();
        this.prefix = prefix.toString();
        this.suffix = suffix.toString();
    }

    public TStringJoiner setEmptyValue(CharSequence emptyValue) {
        this.emptyValue = emptyValue.toString();
        return this;
    }

    public TStringJoiner add(@Nullable CharSequence element) {
        StringBuilder text = joined;
        if (text == null) {
            joined = new StringBuilder().append(element);
        } else {
            text.append(delimiter).append(element);
        }
        return this;
    }

    public TStringJoiner merge(TStringJoiner other) {
        StringBuilder others = other.joined;
        if (others != null) {
            // Taken before appending, in case other is this.
            String elements = others.toString();
            StringBuilder text = joined;
            if (text == null) {
                joined = new StringBuilder(elements);
            } else {
                text.append(delimiter).append(elements);
            }
        }
        return this;
    }

    public int length() {
        return toString().length();
    }

    @Override
    public String toString() {
        StringBuilder text = joined;
        if (text == null) {
            return emptyValue != null ? emptyValue : prefix + suffix;
        }
        return prefix + text + suffix;
    }
}
