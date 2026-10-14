package incari.classlib.substitutes.java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.nio.file.FileStore}, which TeaVM lacks. The game's own virtual file systems extend it;
 * the browser's storage has no stores of its own ({@code Files.getFileStore} throws).
 */
public abstract class TFileStore {

    protected TFileStore() {}

    public abstract String name();

    public abstract String type();

    public abstract boolean isReadOnly();

    public abstract long getTotalSpace() throws IOException;

    public abstract long getUsableSpace() throws IOException;

    public abstract long getUnallocatedSpace() throws IOException;

    public long getBlockSize() throws IOException {
        throw new UnsupportedOperationException();
    }

    public abstract boolean supportsFileAttributeView(Class<? extends FileAttributeView> type);

    public abstract boolean supportsFileAttributeView(String name);

    public abstract <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type);

    public abstract @Nullable Object getAttribute(String attribute) throws IOException;
}
