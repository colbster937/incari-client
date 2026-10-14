package incari.classlib.substitutes.org.lwjgl.stb;

import incari.classlib.memory.Heap;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * LWJGL's {@code STBIWriteCallback}, as {@code NativeImage}'s PNG writer extends it: the encoder
 * ({@link TSTBImageWrite}) hands it the file's bytes in {@link Heap} memory. There are no function
 * pointers in the page, so {@link #address} is a number the encoder looks the callback up by.
 */
@SuppressWarnings("TypeName") // The name is LWJGL's class, as the class library maps it.
public abstract class TSTBIWriteCallback {

    private static final Map<Long, TSTBIWriteCallback> LIVE = new HashMap<>();
    private static long next = 1;

    private long address;

    protected TSTBIWriteCallback() {}

    /** Receives {@code size} bytes of the file at {@code data} ({@link #getData} views them). */
    public abstract void invoke(long context, long data, int size);

    /** The number the encoder calls this callback by, until {@link #free}. */
    public long address() {
        if (address == 0) {
            address = next++;
            LIVE.put(address, this);
        }
        return address;
    }

    public void free() {
        LIVE.remove(address);
        address = 0;
    }

    /** The bytes a call to {@link #invoke} was given. */
    public static ByteBuffer getData(long data, int size) {
        return Heap.buffer(data, size);
    }

    static @Nullable TSTBIWriteCallback find(long address) {
        return LIVE.get(address);
    }
}
