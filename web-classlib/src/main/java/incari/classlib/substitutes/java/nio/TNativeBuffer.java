package incari.classlib.substitutes.java.nio;

/**
 * TeaVM's own {@code java.nio.NativeBuffer} (not a JDK type): a buffer in the module's linear
 * memory that can be freed now rather than when it's collected. TeaVM's direct buffers implement
 * it. Declared here, the same as TeaVM's, so {@link incari.classlib.memory.Heap} can name it:
 * javac knows only the JDK's {@code java.nio}, and the build maps this name onto TeaVM's.
 */
public interface TNativeBuffer {

    void release();
}
