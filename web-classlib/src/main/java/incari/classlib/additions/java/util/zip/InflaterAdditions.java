package incari.classlib.additions.java.util.zip;

import incari.classlib.InstanceMethod;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** {@code java.util.zip.Inflater} methods TeaVM lacks: the buffer forms, over the array forms. */
public final class InflaterAdditions {

    private InflaterAdditions() {}

    /**
     * Copies the remaining input: the array form keeps a reference, and the buffer's position has
     * to move past it now, as the JDK's does once the input is consumed.
     */
    @InstanceMethod
    public static void setInput(Inflater self, ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()];
        input.get(bytes);
        self.setInput(bytes);
    }

    @InstanceMethod
    public static int inflate(Inflater self, ByteBuffer output) throws DataFormatException {
        byte[] bytes = new byte[output.remaining()];
        int written = self.inflate(bytes);
        output.put(bytes, 0, written);
        return written;
    }
}
