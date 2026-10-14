package incari.classlib.substitutes.org.lwjgl.system;

import incari.classlib.memory.Heap;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * LWJGL's {@code MemoryStack}, the part the game uses outside its OpenGL and Vulkan backends, over
 * {@link incari.classlib.memory.Heap}: one block per stack, allocated from the top down, with
 * frames that {@link #close} pops. The page has one thread, so there's one stack. As in LWJGL,
 * {@code malloc} leaves the memory as the last frame did.
 */
public final class TMemoryStack implements AutoCloseable {

    private static final int SIZE = 64 * 1024;
    private static final int ALIGNMENT = 8;
    private static final TMemoryStack STACK = new TMemoryStack();

    private final long base = Heap.allocate(SIZE, false);
    private int pointer = SIZE;
    private int[] frames = new int[16];
    private int depth;

    private TMemoryStack() {}

    public static TMemoryStack stackGet() {
        return STACK;
    }

    public static TMemoryStack stackPush() {
        return STACK.push();
    }

    public TMemoryStack push() {
        if (depth == frames.length) {
            int[] grown = new int[depth * 2];
            System.arraycopy(frames, 0, grown, 0, depth);
            frames = grown;
        }
        frames[depth++] = pointer;
        return this;
    }

    public TMemoryStack pop() {
        pointer = frames[--depth];
        return this;
    }

    @Override
    public void close() {
        pop();
    }

    /** The bytes still free below the stack's top, as LWJGL counts. */
    public int getPointer() {
        return pointer;
    }

    public long nmalloc(int alignment, int size) {
        int start = (pointer - size) & -Math.max(alignment, 1);
        if (start < 0) {
            throw new OutOfMemoryError("Out of stack space");
        }
        pointer = start;
        return base + start;
    }

    public long ncalloc(int alignment, int count, int size) {
        int bytes = count * size;
        long address = nmalloc(alignment, bytes);
        Heap.fill(address, (byte) 0, bytes);
        return address;
    }

    public ByteBuffer malloc(int size) {
        return Heap.buffer(nmalloc(ALIGNMENT, size), size);
    }

    public ByteBuffer calloc(int size) {
        return Heap.buffer(ncalloc(ALIGNMENT, size, 1), size);
    }

    public IntBuffer mallocInt(int count) {
        return Heap.ints(nmalloc(Integer.BYTES, count * Integer.BYTES), count);
    }

    public IntBuffer callocInt(int count) {
        return Heap.ints(ncalloc(Integer.BYTES, count, Integer.BYTES), count);
    }

    public FloatBuffer callocFloat(int count) {
        return calloc(count * Float.BYTES).asFloatBuffer();
    }

    public LongBuffer callocLong(int count) {
        return calloc(count * Long.BYTES).asLongBuffer();
    }

    public IntBuffer ints(int value) {
        IntBuffer buffer = mallocInt(1);
        buffer.put(0, value);
        return buffer;
    }

    public IntBuffer ints(int first, int second) {
        IntBuffer buffer = mallocInt(2);
        buffer.put(0, first).put(1, second);
        return buffer;
    }

    public LongBuffer longs(long value) {
        LongBuffer buffer = calloc(Long.BYTES).asLongBuffer();
        buffer.put(0, value);
        return buffer;
    }

    /** {@code text} in UTF-8 followed by a zero, which is in memory but outside the buffer. */
    public ByteBuffer UTF8(CharSequence text) {
        return encode(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public ByteBuffer ASCII(CharSequence text) {
        return encode(text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private ByteBuffer encode(byte[] bytes) {
        ByteBuffer buffer = calloc(bytes.length + 1);
        buffer.put(bytes).flip();
        buffer.limit(bytes.length);
        return buffer;
    }
}
