package incari.classlib.substitutes.org.lwjgl.util.shaderc;

import incari.classlib.memory.Heap;
import java.nio.ByteBuffer;

/**
 * LWJGL's {@code ShadercIncludeResult} struct, as far as the game's shader include cache
 * ({@code ShaderSource.CachedIncludeSource}) fills and frees it: a name and a text, each a
 * pointer and a length, in {@link Heap} memory with shaderc's 64-bit layout. Only the desktop
 * backends' shader compiler reads it; the browser build translates shaders when it's built.
 */
public final class TShadercIncludeResult {

    public static final int SOURCE_NAME = 0;
    public static final int SOURCE_NAME_LENGTH = 8;
    public static final int CONTENT = 16;
    public static final int CONTENT_LENGTH = 24;
    public static final int USER_DATA = 32;
    public static final int SIZEOF = 40;
    public static final int ALIGNOF = 8;

    private final long address;

    private TShadercIncludeResult(long address) {
        this.address = address;
    }

    public static TShadercIncludeResult calloc() {
        return new TShadercIncludeResult(Heap.allocate(SIZEOF, true));
    }

    public long address() {
        return address;
    }

    /** Points the name at {@code name}'s remaining bytes, which the caller keeps alive. */
    public TShadercIncludeResult source_name(ByteBuffer name) {
        Heap.putLong(address + SOURCE_NAME, Heap.address(name));
        Heap.putLong(address + SOURCE_NAME_LENGTH, name.remaining());
        return this;
    }

    /** Points the text at {@code content}'s remaining bytes, which the caller keeps alive. */
    public TShadercIncludeResult content(ByteBuffer content) {
        Heap.putLong(address + CONTENT, Heap.address(content));
        Heap.putLong(address + CONTENT_LENGTH, content.remaining());
        return this;
    }
}
