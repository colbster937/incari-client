package incari.classlib.additions.java.nio.file;

import incari.classlib.Replaces;
import incari.classlib.nio.FileChannels;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.nio.file.Files} methods TeaVM lacks. The browser's storage is TeaVM's virtual file
 * system: files and directories with modification times, and nothing more (no stores, links,
 * owners or permissions), so those answer as a file system without them does.
 */
public final class FilesAdditions {

    private FilesAdditions() {}

    /** The page's storage isn't a disk with stores; callers report the space as unknown. */
    public static FileStore getFileStore(Path path) throws IOException {
        throw new IOException("No file stores in the browser");
    }

    /**
     * The files under {@code start}, depth first, that {@code matcher} accepts. Replaces TeaVM's,
     * which fails with a {@code NullPointerException} when a directory itself doesn't match and
     * lists matching subtrees twice. The page's file system has no links to follow.
     */
    @Replaces
    public static Stream<Path> find(
            Path start, int maxDepth, BiPredicate<Path, BasicFileAttributes> matcher, FileVisitOption... options)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(start, BasicFileAttributes.class);
        Stream<Path> self = matcher.test(start, attributes) ? Stream.of(start) : Stream.empty();
        if (maxDepth <= 0 || !attributes.isDirectory()) {
            return self;
        }
        List<Path> children;
        try (Stream<Path> listing = Files.list(start)) {
            children = listing.collect(Collectors.toList());
        }
        return Stream.concat(self, children.stream().flatMap(child -> {
            try {
                return find(child, maxDepth - 1, matcher);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    /** Files in the page's storage have no owners; callers treat that as an unreadable property. */
    public static UserPrincipal getOwner(Path path, LinkOption... options) throws IOException {
        throw new IOException("No file owners in the browser");
    }

    public static @Nullable String probeContentType(Path path) {
        return null;
    }

    /** A {@code FileChannel} over TeaVM's virtual file system (see {@link FileChannels}). */
    public static SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return FileChannels.open(path, options, attrs);
    }

    public static SeekableByteChannel newByteChannel(Path path, OpenOption... options) throws IOException {
        return FileChannels.open(path, new HashSet<>(Arrays.asList(options)));
    }

    public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
        if (!path.toFile().setLastModified(time.toMillis())) {
            throw new IOException("Couldn't set the modification time of " + path);
        }
        return path;
    }

    public static Path createLink(Path link, Path existing) throws IOException {
        throw new UnsupportedOperationException("The browser's file system has no links");
    }

    public static Set<PosixFilePermission> getPosixFilePermissions(Path path, LinkOption... options) {
        throw new UnsupportedOperationException("The browser's file system has no permissions");
    }

    public static Path setPosixFilePermissions(Path path, Set<PosixFilePermission> permissions) {
        throw new UnsupportedOperationException("The browser's file system has no permissions");
    }

    /** Only the basic attributes exist; views of any kind aren't available. */
    public static <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    /** The basic attributes by name ({@code basic:size}, {@code lastModifiedTime}...). */
    public static @Nullable Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        int colon = attribute.indexOf(':');
        String view = colon < 0 ? "basic" : attribute.substring(0, colon);
        String name = attribute.substring(colon + 1);
        if (!view.equals("basic")) {
            throw new UnsupportedOperationException("View '" + view + "' not available");
        }
        return switch (name) {
            case "size" -> Files.size(path);
            case "lastModifiedTime", "lastAccessTime", "creationTime" -> Files.getLastModifiedTime(path);
            case "isDirectory" -> Files.isDirectory(path);
            case "isRegularFile" -> Files.isRegularFile(path);
            case "isSymbolicLink" -> false;
            case "isOther" -> false;
            case "fileKey" -> null;
            default -> throw new IllegalArgumentException("'" + name + "' not recognized");
        };
    }
}
