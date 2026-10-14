package incari.classlib.substitutes.org.lwjgl.system;

/**
 * LWJGL's {@code MemoryUtil.MemoryAllocator}, as far as the game uses it ({@code ByteBufferBuilder}).
 * A top-level class named for the nested one, so it takes the name
 * {@code org.lwjgl.system.MemoryUtil$MemoryAllocator}.
 */
@SuppressWarnings("TypeName") // The name is LWJGL's nested class, as the class library maps it.
public interface TMemoryUtil$MemoryAllocator {

    long malloc(long size);

    long calloc(long count, long size);

    long realloc(long address, long size);

    void free(long address);
}
