package incari.classlib.substitutes.java.nio.channels;

import incari.classlib.nio.FileChannels;
import incari.classlib.substitutes.java.nio.channels.spi.TAbstractInterruptibleChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code java.nio.channels.FileChannel}, which TeaVM lacks: a channel for reading, writing and
 * locking a file, with a position that reads and writes advance.
 *
 * <p>In the browser, {@link #open} works over TeaVM's virtual file system (see
 * {@code incari.classlib.nio.FileChannels}). Locks are granted at once, since one page is one
 * process, but overlapping locks on a file are still refused as the JDK refuses them. Memory
 * mapping is unavailable: a page can't map a file into memory.
 *
 * <p>Omitted: {@code map(MapMode, long, long, Arena)}, since TeaVM has no foreign memory API.
 */
public abstract class TFileChannel extends TAbstractInterruptibleChannel
        implements SeekableByteChannel, TGatheringByteChannel, TScatteringByteChannel {

    protected TFileChannel() {}

    public static TFileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return FileChannels.open(path, options, attrs);
    }

    public static TFileChannel open(Path path, OpenOption... options) throws IOException {
        // Repeats are allowed here, as in the JDK; a null option fails in FileChannels.open.
        return FileChannels.open(path, new HashSet<>(Arrays.asList(options)));
    }

    @Override
    public abstract int read(ByteBuffer dst) throws IOException;

    @Override
    public abstract long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

    @Override
    public final long read(ByteBuffer[] dsts) throws IOException {
        return read(dsts, 0, dsts.length);
    }

    @Override
    public abstract int write(ByteBuffer src) throws IOException;

    @Override
    public abstract long write(ByteBuffer[] srcs, int offset, int length) throws IOException;

    @Override
    public final long write(ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }

    @Override
    public abstract long position() throws IOException;

    @Override
    public abstract TFileChannel position(long newPosition) throws IOException;

    @Override
    public abstract long size() throws IOException;

    @Override
    public abstract TFileChannel truncate(long size) throws IOException;

    public abstract void force(boolean metaData) throws IOException;

    public abstract long transferTo(long position, long count, WritableByteChannel target) throws IOException;

    public abstract long transferFrom(ReadableByteChannel src, long position, long count) throws IOException;

    public abstract int read(ByteBuffer dst, long position) throws IOException;

    public abstract int write(ByteBuffer src, long position) throws IOException;

    public abstract MappedByteBuffer map(MapMode mode, long position, long size) throws IOException;

    public abstract TFileLock lock(long position, long size, boolean shared) throws IOException;

    public final TFileLock lock() throws IOException {
        return lock(0L, Long.MAX_VALUE, false);
    }

    public abstract TFileLock tryLock(long position, long size, boolean shared) throws IOException;

    public final TFileLock tryLock() throws IOException {
        return tryLock(0L, Long.MAX_VALUE, false);
    }

    /** {@code java.nio.channels.FileChannel.MapMode}: how {@link #map} would map a file. */
    public static class MapMode {

        public static final MapMode READ_ONLY = new MapMode("READ_ONLY");
        public static final MapMode READ_WRITE = new MapMode("READ_WRITE");
        public static final MapMode PRIVATE = new MapMode("PRIVATE");

        private final String name;

        private MapMode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
