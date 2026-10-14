package incari.classlib.additions.java.util;

import java.util.Base64;

/**
 * {@code java.util.Base64} methods TeaVM lacks. Approximate: the MIME coders here are the basic
 * ones, without line breaks and without skipping them when decoding. Their only callers are the
 * online-mode key parsing the browser build doesn't use.
 */
public final class Base64Additions {

    private Base64Additions() {}

    public static Base64.Decoder getMimeDecoder() {
        return Base64.getDecoder();
    }

    public static Base64.Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
        return Base64.getEncoder();
    }
}
