package incari.classlib.nio;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The browser build's {@code jar} file system provider, which the game looks up at startup
 * ({@code Util.ZIP_FILE_SYSTEM_PROVIDER}) for writing zip files (world exports, debug reports).
 * There's no zip file system: every operation refuses, so profiling reports can't be written
 * (ADR 0011). Resource packs in zips are read through {@code ZipFile}, which works.
 */
public final class ZipFileSystemProvider extends FileSystemProvider {

    @Override
    public String getScheme() {
        return "jar";
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Zip files aren't supported in the browser build yet");
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw unsupported();
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) {
        throw unsupported();
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        throw unsupported();
    }

    @Override
    public Path getPath(URI uri) {
        throw unsupported();
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
        throw unsupported();
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
        throw unsupported();
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw unsupported();
    }

    @Override
    public void delete(Path path) {
        throw unsupported();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw unsupported();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw unsupported();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        throw unsupported();
    }

    @Override
    public boolean isHidden(Path path) {
        throw unsupported();
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new IOException("Zip files aren't supported in the browser build yet");
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) {
        throw unsupported();
    }

    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) {
        throw unsupported();
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
        throw unsupported();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw unsupported();
    }
}
