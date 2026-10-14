package incari.classlib.memory;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.teavm.interop.PlatformMarker;
import org.teavm.interop.Platforms;
import org.teavm.jso.JSBody;

/**
 * Off-heap memory for LWJGL's {@code MemoryUtil}, {@code MemoryStack} and {@code BufferUtils},
 * with addresses as the game's vertex builders and images use them.
 *
 * <p>In the browser, memory is the module's linear memory (the {@code ArrayBuffer} JavaScript
 * sees). Each allocation is a TeaVM direct buffer, its address is its real offset, and a read or
 * write is one WebAssembly load or store ({@link LinearMemory}). A view ({@code memByteBuffer}) is
 * a slice of its block, so WebGL uploads it from linear memory without a copy.
 *
 * <p>On a JVM (the tests), the same bookkeeping runs over heap buffers with made-up addresses, and
 * {@code malloc} memory is filled with junk, as reused linear memory would be, so tests catch code
 * that forgets to clear it.
 *
 * <p>Blocks are kept by start address; views and the JVM's accesses find a block from an address
 * inside it with a floor lookup. One thread at a time: the page's, where Java threads are
 * coroutines and nothing here suspends.
 */
public final class Heap {

    /** What the JVM backend fills unzeroed memory with. */
    static final byte JUNK = (byte) 0xA5;

    private static final TreeMap<Long, ByteBuffer> blocks = new TreeMap<>();

    // The JVM backend's addresses: block starts by backing array, and int views by identity.
    private static final IdentityHashMap<byte[], Long> jvmStarts = new IdentityHashMap<>();
    private static final Map<Long, byte[]> jvmArrays = new HashMap<>();
    private static final IdentityHashMap<IntBuffer, Long> jvmInts = new IdentityHashMap<>();
    private static long jvmNext = 1L << 32;

    // Bytes in blocks not yet freed: the profiler's "linear memory in use". Linear memory
    // itself only grows, so its size is a high-water mark and says little about what's held now.
    private static long live;

    private Heap() {}

    /** Bytes in blocks allocated and not yet freed (buffers the collector frees aren't counted). */
    public static long liveBytes() {
        return live;
    }

    /**
     * True in the WebAssembly build, false on a JVM. TeaVM replaces calls to it with the constant,
     * so each branch on it costs nothing and the other backend isn't compiled in.
     */
    @PlatformMarker(Platforms.WEBASSEMBLY_GC)
    static boolean linear() {
        return false;
    }

    /** Allocates {@code size} bytes, zeroed if {@code zeroed} ({@code calloc}), else not ({@code malloc}). */
    public static long allocate(long size, boolean zeroed) {
        if (size < 0 || size > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Can't allocate " + size + " bytes");
        }
        // Never zero bytes, so every block has its own start address.
        int capacity = (int) Math.max(size, 1);
        ByteBuffer block;
        long address;
        if (linear()) {
            block = ByteBuffer.allocateDirect(capacity);
            address = LinearMemory.address(block);
            if (zeroed) {
                // TeaVM's allocator hands out freed memory as it was.
                LinearMemory.fill(address, (byte) 0, capacity);
            }
        } else {
            byte[] array = new byte[capacity];
            if (!zeroed) {
                Arrays.fill(array, JUNK);
            }
            block = ByteBuffer.wrap(array);
            address = jvmNext;
            jvmNext += (capacity + 15L) & -16L;
            jvmStarts.put(array, address);
            jvmArrays.put(address, array);
        }
        blocks.put(address, block.order(ByteOrder.LITTLE_ENDIAN));
        counted(capacity);
        return address;
    }

    /** Frees the block {@code address} starts; 0 is ignored, as {@code free(NULL)} is. */
    public static void free(long address) {
        if (address == 0) {
            return;
        }
        ByteBuffer block = blocks.remove(address);
        if (block == null) {
            throw new IllegalArgumentException("Not the start of a block: " + Long.toHexString(address));
        }
        counted(-block.capacity());
        if (linear()) {
            // Now rather than when the collector gets to the buffer: linear memory never shrinks.
            LinearMemory.release(block);
        } else {
            jvmStarts.remove(jvmArrays.remove(address));
        }
    }

    private static void counted(long bytes) {
        live += bytes;
        if (linear()) {
            publish((double) live);
        }
    }

    // Where the benchmark runner reads it (window.__incariLinear.live), with the peak beside it.
    @JSBody(
            params = "live",
            script = "var c = self.__incariLinear || (self.__incariLinear = {live: 0, peak: 0});"
                    + " c.live = live; if (live > c.peak) c.peak = live;")
    private static native void publish(double live);

    /** Moves the block {@code address} starts into a new one of {@code size} bytes; 0 allocates. */
    public static long reallocate(long address, long size) {
        long moved = allocate(size, false);
        if (address != 0) {
            copy(address, moved, Math.min(block(address).capacity(), size));
            free(address);
        }
        return moved;
    }

    /** The {@code size} bytes at {@code address}, which must lie in one block, as a little-endian buffer. */
    public static ByteBuffer buffer(long address, int size) {
        Map.Entry<Long, ByteBuffer> entry = blocks.floorEntry(address);
        long offset = entry == null ? -1 : address - entry.getKey();
        if (entry == null || size < 0 || offset + size > entry.getValue().capacity()) {
            throw new IllegalArgumentException("Not allocated: " + size + " bytes at " + Long.toHexString(address));
        }
        return entry.getValue().slice((int) offset, size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A new block as a buffer. */
    public static ByteBuffer allocateBuffer(int size, boolean zeroed) {
        return buffer(allocate(size, zeroed), size);
    }

    /**
     * A zeroed buffer the collector frees, as LWJGL's {@code BufferUtils} gives: never passed to
     * {@link #free}, so the heap doesn't keep it. Its address is still valid while it lives.
     */
    public static ByteBuffer collectedBuffer(int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        if (linear()) {
            LinearMemory.fill(LinearMemory.address(buffer), (byte) 0, size);
        }
        return buffer;
    }

    /** The address of {@code buffer}'s position. */
    public static long address(ByteBuffer buffer) {
        if (linear()) {
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException("Not a buffer in linear memory: " + buffer);
            }
            return LinearMemory.address(buffer) + buffer.position();
        }
        Long start = buffer.hasArray() ? jvmStarts.get(buffer.array()) : null;
        if (start == null) {
            throw new IllegalArgumentException("Not a buffer from MemoryUtil: " + buffer);
        }
        return start + buffer.arrayOffset() + buffer.position();
    }

    /** An int view of a new block. */
    public static IntBuffer allocateInts(int count, boolean zeroed) {
        return ints(allocate((long) count * Integer.BYTES, zeroed), count);
    }

    /** An int view of {@code count} ints at {@code address}. */
    public static IntBuffer ints(long address, int count) {
        IntBuffer ints = buffer(address, count * Integer.BYTES).asIntBuffer();
        if (!linear()) {
            // A JVM's int views don't say what they view; remembered so memAddress finds it (tests only).
            jvmInts.put(ints, address);
        }
        return ints;
    }

    /** The address of an int buffer's position: any in linear memory, or one from {@link #ints} on a JVM. */
    public static long address(IntBuffer buffer) {
        long start;
        if (linear()) {
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException("Not a buffer in linear memory: " + buffer);
            }
            start = LinearMemory.address(buffer);
        } else {
            Long known = jvmInts.get(buffer);
            if (known == null) {
                throw new IllegalArgumentException("Not a buffer from MemoryUtil.memAllocInt: " + buffer);
            }
            start = known;
        }
        return start + (long) buffer.position() * Integer.BYTES;
    }

    /** Frees the block a buffer from this heap starts at its position 0. */
    public static void free(Buffer buffer) {
        if (buffer instanceof ByteBuffer bytes) {
            free(address(bytes) - bytes.position());
        } else if (buffer instanceof IntBuffer ints) {
            long start = address(ints) - (long) ints.position() * Integer.BYTES;
            if (!linear()) {
                jvmInts.remove(ints);
            }
            free(start);
        } else {
            throw new IllegalArgumentException("Can't free " + buffer);
        }
    }

    // --- Reads and writes: one load or store in the browser -----------------------------------

    /** Copies {@code count} bytes; the ranges may overlap, as {@code memmove}'s may. */
    public static void copy(long source, long target, long count) {
        if (count <= 0) {
            return;
        }
        if (linear()) {
            LinearMemory.copy(source, target, (int) count);
        } else {
            // Views of blocks: ByteBuffer.put copies as if through a temporary, so overlaps are safe.
            int length = (int) count;
            buffer(target, length).put(0, buffer(source, length), 0, length);
        }
    }

    /** Sets {@code count} bytes to {@code value}. */
    public static void fill(long address, byte value, long count) {
        if (count <= 0) {
            return;
        }
        if (linear()) {
            LinearMemory.fill(address, value, (int) count);
        } else {
            ByteBuffer view = buffer(address, (int) count);
            for (int i = 0; i < count; i++) {
                view.put(i, value);
            }
        }
    }

    public static byte getByte(long address) {
        if (linear()) {
            return LinearMemory.getByte(address);
        }
        return buffer(address, 1).get(0);
    }

    public static void putByte(long address, byte value) {
        if (linear()) {
            LinearMemory.putByte(address, value);
        } else {
            buffer(address, 1).put(0, value);
        }
    }

    public static short getShort(long address) {
        if (linear()) {
            return LinearMemory.getShort(address);
        }
        return buffer(address, 2).getShort(0);
    }

    public static void putShort(long address, short value) {
        if (linear()) {
            LinearMemory.putShort(address, value);
        } else {
            buffer(address, 2).putShort(0, value);
        }
    }

    public static int getInt(long address) {
        if (linear()) {
            return LinearMemory.getInt(address);
        }
        return buffer(address, 4).getInt(0);
    }

    public static void putInt(long address, int value) {
        if (linear()) {
            LinearMemory.putInt(address, value);
        } else {
            buffer(address, 4).putInt(0, value);
        }
    }

    public static long getLong(long address) {
        if (linear()) {
            return LinearMemory.getLong(address);
        }
        return buffer(address, 8).getLong(0);
    }

    public static void putLong(long address, long value) {
        if (linear()) {
            LinearMemory.putLong(address, value);
        } else {
            buffer(address, 8).putLong(0, value);
        }
    }

    public static float getFloat(long address) {
        if (linear()) {
            return LinearMemory.getFloat(address);
        }
        return buffer(address, 4).getFloat(0);
    }

    public static void putFloat(long address, float value) {
        if (linear()) {
            LinearMemory.putFloat(address, value);
        } else {
            buffer(address, 4).putFloat(0, value);
        }
    }

    public static double getDouble(long address) {
        if (linear()) {
            return LinearMemory.getDouble(address);
        }
        return buffer(address, 8).getDouble(0);
    }

    public static void putDouble(long address, double value) {
        if (linear()) {
            LinearMemory.putDouble(address, value);
        } else {
            buffer(address, 8).putDouble(0, value);
        }
    }

    // --- Strings -------------------------------------------------------------------------------

    /** A zero-terminated string starting at {@code address}. */
    public static String string(long address, boolean ascii) {
        int length = 0;
        while (getByte(address + length) != 0) {
            length++;
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = getByte(address + i);
        }
        return new String(bytes, ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8);
    }

    /** {@code text} encoded in a new block, followed by a zero if {@code terminated}. */
    public static ByteBuffer encode(CharSequence text, boolean terminated, boolean ascii) {
        byte[] bytes = text.toString().getBytes(ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8);
        ByteBuffer buffer = allocateBuffer(bytes.length + (terminated ? 1 : 0), false);
        buffer.put(bytes);
        if (terminated) {
            buffer.put((byte) 0);
        }
        buffer.flip();
        // The terminator is in memory but, as in LWJGL, outside the buffer.
        buffer.limit(bytes.length);
        return buffer;
    }

    // --- Blocks --------------------------------------------------------------------------------

    /** The block {@code address} starts. */
    private static ByteBuffer block(long address) {
        ByteBuffer block = blocks.get(address);
        if (block == null) {
            throw new IllegalArgumentException("Not the start of a block: " + Long.toHexString(address));
        }
        return block;
    }
}
