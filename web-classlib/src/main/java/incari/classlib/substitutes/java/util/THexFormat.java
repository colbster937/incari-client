package incari.classlib.substitutes.java.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * {@code java.util.HexFormat}, which TeaVM lacks: formats bytes and numbers as hexadecimal and parses
 * them back, with an optional delimiter between bytes and a prefix and suffix around each.
 */
public final class THexFormat {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();
    private static final THexFormat HEX_FORMAT = new THexFormat("", "", "", false);

    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final boolean upperCase;

    private THexFormat(String delimiter, String prefix, String suffix, boolean upperCase) {
        this.delimiter = delimiter;
        this.prefix = prefix;
        this.suffix = suffix;
        this.upperCase = upperCase;
    }

    public static THexFormat of() {
        return HEX_FORMAT;
    }

    public static THexFormat ofDelimiter(String delimiter) {
        return new THexFormat(delimiter, "", "", false);
    }

    public THexFormat withDelimiter(String delimiter) {
        return new THexFormat(delimiter, prefix, suffix, upperCase);
    }

    public THexFormat withPrefix(String prefix) {
        return new THexFormat(delimiter, prefix, suffix, upperCase);
    }

    public THexFormat withSuffix(String suffix) {
        return new THexFormat(delimiter, prefix, suffix, upperCase);
    }

    public THexFormat withUpperCase() {
        return new THexFormat(delimiter, prefix, suffix, true);
    }

    public THexFormat withLowerCase() {
        return new THexFormat(delimiter, prefix, suffix, false);
    }

    public String delimiter() {
        return delimiter;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean isUpperCase() {
        return upperCase;
    }

    // --- Byte arrays -------------------------------------------------------------------------------

    public String formatHex(byte[] bytes) {
        return formatHex(bytes, 0, bytes.length);
    }

    public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
        return formatHex(new StringBuilder(), bytes, fromIndex, toIndex).toString();
    }

    public <A extends Appendable> A formatHex(A out, byte[] bytes) {
        return formatHex(out, bytes, 0, bytes.length);
    }

    public <A extends Appendable> A formatHex(A out, byte[] bytes, int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, bytes.length);
        try {
            for (int i = fromIndex; i < toIndex; i++) {
                if (i > fromIndex) {
                    out.append(delimiter);
                }
                out.append(prefix);
                out.append(toHighHexDigit(bytes[i]));
                out.append(toLowHexDigit(bytes[i]));
                out.append(suffix);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public byte[] parseHex(CharSequence string) {
        return parseHex(string, 0, string.length());
    }

    public byte[] parseHex(CharSequence string, int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, string.length());
        if (fromIndex == toIndex) {
            return new byte[0];
        }
        int stride = prefix.length() + 2 + suffix.length() + delimiter.length();
        int length = toIndex - fromIndex + delimiter.length();
        if (length % stride != 0) {
            throw new IllegalArgumentException("extra or missing delimiters or values consisting of prefix,"
                    + " two hexadecimal digits, and suffix");
        }
        byte[] bytes = new byte[length / stride];
        int at = fromIndex;
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                at = expect(string, at, delimiter);
            }
            at = expect(string, at, prefix);
            bytes[i] = (byte) ((fromHexDigit(string.charAt(at)) << 4) | fromHexDigit(string.charAt(at + 1)));
            at = expect(string, at + 2, suffix);
        }
        return bytes;
    }

    public byte[] parseHex(char[] chars, int fromIndex, int toIndex) {
        return parseHex(new String(chars), fromIndex, toIndex);
    }

    private static int expect(CharSequence string, int at, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (string.charAt(at + i) != text.charAt(i)) {
                throw new IllegalArgumentException("found: \"" + string.subSequence(at, at + text.length())
                        + "\", expected: \"" + text + "\", index: " + at);
            }
        }
        return at + text.length();
    }

    // --- Single values -------------------------------------------------------------------------------

    public char toLowHexDigit(int value) {
        return (upperCase ? UPPER : LOWER)[value & 0xF];
    }

    public char toHighHexDigit(int value) {
        return (upperCase ? UPPER : LOWER)[(value >> 4) & 0xF];
    }

    public <A extends Appendable> A toHexDigits(A out, byte value) {
        try {
            out.append(toHighHexDigit(value));
            out.append(toLowHexDigit(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public String toHexDigits(byte value) {
        return digits(value, 2);
    }

    public String toHexDigits(char value) {
        return digits(value, 4);
    }

    public String toHexDigits(short value) {
        return digits(value, 4);
    }

    public String toHexDigits(int value) {
        return digits(value, 8);
    }

    public String toHexDigits(long value) {
        return digits(value, 16);
    }

    public String toHexDigits(long value, int digits) {
        if (digits < 0 || digits > 16) {
            throw new IllegalArgumentException("number of digits: " + digits);
        }
        return digits(value, digits);
    }

    private String digits(long value, int count) {
        char[] table = upperCase ? UPPER : LOWER;
        char[] out = new char[count];
        for (int i = count - 1; i >= 0; i--) {
            out[i] = table[(int) (value & 0xF)];
            value >>>= 4;
        }
        return new String(out);
    }

    public static boolean isHexDigit(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    public static int fromHexDigit(int ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
    }

    public static int fromHexDigits(CharSequence string) {
        return fromHexDigits(string, 0, string.length());
    }

    public static int fromHexDigits(CharSequence string, int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, string.length());
        if (toIndex - fromIndex > 8) {
            throw new IllegalArgumentException("string length greater than 8: " + (toIndex - fromIndex));
        }
        return (int) parse(string, fromIndex, toIndex);
    }

    public static long fromHexDigitsToLong(CharSequence string) {
        return fromHexDigitsToLong(string, 0, string.length());
    }

    public static long fromHexDigitsToLong(CharSequence string, int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, string.length());
        if (toIndex - fromIndex > 16) {
            throw new IllegalArgumentException("string length greater than 16: " + (toIndex - fromIndex));
        }
        return parse(string, fromIndex, toIndex);
    }

    private static long parse(CharSequence string, int fromIndex, int toIndex) {
        long value = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            value = (value << 4) | fromHexDigit(string.charAt(i));
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof THexFormat format
                && format.delimiter.equals(delimiter)
                && format.prefix.equals(prefix)
                && format.suffix.equals(suffix)
                && format.upperCase == upperCase;
    }

    @Override
    public int hashCode() {
        return Objects.hash(delimiter, prefix, suffix, upperCase);
    }

    @Override
    public String toString() {
        return "uppercase: " + upperCase + ", delimiter: \"" + delimiter + "\", prefix: \"" + prefix + "\", suffix: \""
                + suffix + "\"";
    }
}
