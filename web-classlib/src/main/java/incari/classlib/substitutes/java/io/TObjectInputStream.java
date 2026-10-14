package incari.classlib.substitutes.java.io;

import java.io.InputStream;

/**
 * {@code java.io.ObjectInputStream}: present only so libraries that probe for it by reflection
 * link (Gson looks for its instance allocator and falls back when the lookup fails). The browser
 * build has no Java serialization.
 */
public class TObjectInputStream extends InputStream {

    protected TObjectInputStream() {}

    @Override
    public int read() {
        throw unsupported();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("The browser build has no Java serialization");
    }
}
