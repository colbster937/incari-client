package incari.classlib.additions.java.lang;

import incari.classlib.Constructor;
import incari.classlib.InstanceMethod;
import java.util.Arrays;
import java.util.stream.Stream;

/** {@code java.lang.String} methods TeaVM lacks. */
public final class StringAdditions {

    private StringAdditions() {}

    /**
     * {@code String(byte[] ascii, int hibyte, int offset, int count)}: each byte is a character's
     * low eight bits and {@code hibyte} the high eight. Deprecated in the JDK, but Netty's
     * {@code ByteBufUtil} decodes ASCII and Latin-1 with it.
     */
    @Constructor
    public static char[] characters(byte[] ascii, int hibyte, int offset, int count) {
        if (offset < 0 || count < 0 || offset > ascii.length - count) {
            throw new StringIndexOutOfBoundsException(
                    "offset " + offset + ", count " + count + ", length " + ascii.length);
        }
        char high = (char) ((hibyte & 0xFF) << 8);
        char[] characters = new char[count];
        for (int i = 0; i < count; i++) {
            characters[i] = (char) (high | (ascii[offset + i] & 0xFF));
        }
        return characters;
    }

    /** The lines of the string, split at {@code \n}, {@code \r} or {@code \r\n}. */
    @InstanceMethod
    public static Stream<String> lines(String self) {
        if (self.isEmpty()) {
            return Stream.empty();
        }
        String[] parts = self.split("\r\n|\r|\n", -1);
        char last = self.charAt(self.length() - 1);
        // A trailing line terminator ends the last line rather than starting an empty one.
        int count = last == '\n' || last == '\r' ? parts.length - 1 : parts.length;
        return Arrays.stream(parts, 0, count);
    }
}
