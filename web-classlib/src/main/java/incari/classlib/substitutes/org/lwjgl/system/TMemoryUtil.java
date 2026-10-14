package incari.classlib.substitutes.org.lwjgl.system;

import incari.classlib.memory.Heap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;

/**
 * LWJGL's {@code MemoryUtil}, the part the game uses outside its OpenGL and Vulkan backends (native
 * images, vertex building, fonts, shader includes), over {@link Heap}: in the browser, the module's
 * linear memory. As in LWJGL, {@code malloc}-style methods leave memory as it was and
 * {@code calloc}-style ones zero it. Members it lacks stay removed and throw when reached.
 *
 * <p>Hot path: the {@code memPut}/{@code memGet} methods are what {@code BufferBuilder} calls for
 * every vertex element; each is one WebAssembly store or load.
 */
public final class TMemoryUtil {

    private static final TMemoryUtil$MemoryAllocator ALLOCATOR = new TMemoryUtil$MemoryAllocator() {
        @Override
        public long malloc(long size) {
            return Heap.allocate(size, false);
        }

        @Override
        public long calloc(long count, long size) {
            return Heap.allocate(count * size, true);
        }

        @Override
        public long realloc(long address, long size) {
            return Heap.reallocate(address, size);
        }

        @Override
        public void free(long address) {
            Heap.free(address);
        }
    };

    private TMemoryUtil() {}

    @SuppressWarnings("unused") // LWJGL's debug tracking; the browser build has none.
    public static TMemoryUtil$MemoryAllocator getAllocator(boolean tracked) {
        return ALLOCATOR;
    }

    public static long nmemAlloc(long size) {
        return Heap.allocate(size, false);
    }

    public static long nmemCalloc(long count, long size) {
        return Heap.allocate(count * size, true);
    }

    public static long nmemRealloc(long address, long size) {
        return Heap.reallocate(address, size);
    }

    public static void nmemFree(long address) {
        Heap.free(address);
    }

    public static ByteBuffer memAlloc(int size) {
        return Heap.allocateBuffer(size, false);
    }

    public static ByteBuffer memCalloc(int size) {
        return Heap.allocateBuffer(size, true);
    }

    public static IntBuffer memAllocInt(int count) {
        return Heap.allocateInts(count, false);
    }

    public static IntBuffer memCallocInt(int count) {
        return Heap.allocateInts(count, true);
    }

    /** The buffer's contents moved to a block of {@code size} bytes, keeping its position, as LWJGL does. */
    public static ByteBuffer memRealloc(@Nullable ByteBuffer buffer, int size) {
        if (buffer == null) {
            return memAlloc(size);
        }
        int position = Math.min(buffer.position(), size);
        long moved = Heap.reallocate(Heap.address(buffer) - buffer.position(), size);
        ByteBuffer result = Heap.buffer(moved, size);
        result.position(position);
        return result;
    }

    public static void memFree(@Nullable ByteBuffer buffer) {
        if (buffer != null) {
            Heap.free(buffer);
        }
    }

    public static void memFree(@Nullable IntBuffer buffer) {
        if (buffer != null) {
            Heap.free(buffer);
        }
    }

    public static long memAddress(ByteBuffer buffer) {
        return Heap.address(buffer);
    }

    public static long memAddress(IntBuffer buffer) {
        return Heap.address(buffer);
    }

    /** As LWJGL: 0 for a null buffer. */
    public static long memAddressSafe(@Nullable ByteBuffer buffer) {
        return buffer == null ? 0 : Heap.address(buffer);
    }

    public static ByteBuffer memByteBuffer(long address, int size) {
        return Heap.buffer(address, size);
    }

    /** The bytes of {@code buffer}'s remaining ints. */
    public static ByteBuffer memByteBuffer(IntBuffer buffer) {
        return Heap.buffer(Heap.address(buffer), buffer.remaining() * Integer.BYTES);
    }

    public static IntBuffer memIntBuffer(long address, int count) {
        return Heap.ints(address, count);
    }

    /** {@code capacity} bytes from {@code offset} past the buffer's position. */
    public static ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity) {
        return Heap.buffer(Heap.address(buffer) + offset, capacity);
    }

    /** Copies {@code count} bytes; the ranges may overlap. */
    public static void memCopy(long source, long target, long count) {
        Heap.copy(source, target, count);
    }

    /** Copies {@code source}'s remaining bytes to {@code target}'s position; neither moves. */
    public static void memCopy(ByteBuffer source, ByteBuffer target) {
        if (target.remaining() < source.remaining()) {
            throw new IllegalArgumentException("Target too small: " + target.remaining() + " < " + source.remaining());
        }
        Heap.copy(Heap.address(source), Heap.address(target), source.remaining());
    }

    public static void memSet(long address, int value, long count) {
        Heap.fill(address, (byte) value, count);
    }

    /** Sets {@code buffer}'s remaining bytes to {@code value}. */
    public static void memSet(ByteBuffer buffer, int value) {
        Heap.fill(Heap.address(buffer), (byte) value, buffer.remaining());
    }

    public static byte memGetByte(long address) {
        return Heap.getByte(address);
    }

    public static short memGetShort(long address) {
        return Heap.getShort(address);
    }

    public static int memGetInt(long address) {
        return Heap.getInt(address);
    }

    public static long memGetLong(long address) {
        return Heap.getLong(address);
    }

    public static float memGetFloat(long address) {
        return Heap.getFloat(address);
    }

    public static double memGetDouble(long address) {
        return Heap.getDouble(address);
    }

    /** A pointer, which is eight bytes here as in 64-bit LWJGL (the browser's are 32-bit values). */
    public static long memGetAddress(long address) {
        return Heap.getLong(address);
    }

    public static void memPutByte(long address, byte value) {
        Heap.putByte(address, value);
    }

    public static void memPutShort(long address, short value) {
        Heap.putShort(address, value);
    }

    public static void memPutInt(long address, int value) {
        Heap.putInt(address, value);
    }

    public static void memPutLong(long address, long value) {
        Heap.putLong(address, value);
    }

    public static void memPutFloat(long address, float value) {
        Heap.putFloat(address, value);
    }

    public static void memPutDouble(long address, double value) {
        Heap.putDouble(address, value);
    }

    public static void memPutAddress(long address, long value) {
        Heap.putLong(address, value);
    }

    public static String memUTF8(long address) {
        return Heap.string(address, false);
    }

    public static String memASCII(long address) {
        return Heap.string(address, true);
    }

    public static ByteBuffer memUTF8(CharSequence text) {
        return Heap.encode(text, true, false);
    }

    public static ByteBuffer memUTF8(CharSequence text, boolean terminated) {
        return Heap.encode(text, terminated, false);
    }

    public static ByteBuffer memASCII(CharSequence text) {
        return Heap.encode(text, true, true);
    }
}
