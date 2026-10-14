package incari.classlib.substitutes.java.nio.channels;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.DELETE_ON_CLOSE;
import static java.nio.file.StandardOpenOption.DSYNC;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TFileChannel} against the JDK's {@link FileChannel}, on real files. */
class FileChannelTest {

    @TempDir
    Path dir;

    private Path file(String name, int... content) throws IOException {
        byte[] bytes = new byte[content.length];
        for (int i = 0; i < content.length; i++) {
            bytes[i] = (byte) content[i];
        }
        return Files.write(dir.resolve(name), bytes);
    }

    // --- Opening -----------------------------------------------------------------------------------

    @Test
    void readsByDefault() throws IOException {
        Path path = file("a", 1, 2, 3);
        try (TFileChannel ch = TFileChannel.open(path)) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            assertThat(ch.read(buffer)).isEqualTo(3);
            assertThatThrownBy(() -> ch.write(ByteBuffer.wrap(new byte[1])))
                    .isInstanceOf(NonWritableChannelException.class);
        }
    }

    @Test
    void refusesWhatTheJdkRefuses() throws IOException {
        Path path = file("a", 1);
        Path missing = dir.resolve("missing");
        List<List<OpenOption>> cases = List.of(
                List.of(READ, APPEND),
                List.of(APPEND, TRUNCATE_EXISTING),
                List.of(WRITE, CREATE_NEW),
                List.of(READ, CREATE),
                List.of(WRITE));
        for (List<OpenOption> options : cases) {
            for (String target : List.of("existing", "missing", "no/such/dir")) {
                // Each side gets its own files, since an open may create one.
                Path jdkDir = Files.createTempDirectory(dir, "jdk");
                Path ourDir = Files.createTempDirectory(dir, "ours");
                Files.write(jdkDir.resolve("existing"), new byte[] {1});
                Files.write(ourDir.resolve("existing"), new byte[] {1});
                Class<?> jdk = thrown(() -> FileChannel.open(jdkDir.resolve(target), Set.copyOf(options))
                        .close());
                Class<?> ours = thrown(() -> TFileChannel.open(ourDir.resolve(target), Set.copyOf(options))
                        .close());
                assertThat(ours).as("%s on %s", options, target).isEqualTo(jdk);
                assertThat(Files.exists(ourDir.resolve(target))).isEqualTo(Files.exists(jdkDir.resolve(target)));
            }
        }
        assertThat(thrown(() -> TFileChannel.open(path, WRITE, CREATE_NEW)))
                .isEqualTo(FileAlreadyExistsException.class);
        assertThat(thrown(() -> TFileChannel.open(missing))).isEqualTo(NoSuchFileException.class);
        assertThat(thrown(() -> TFileChannel.open(dir, WRITE))).isEqualTo(FileSystemException.class);
        assertThatThrownBy(() -> TFileChannel.open(
                        dir.resolve("b"),
                        Set.of(WRITE, CREATE),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aDirectoryOpensForReadingButDoesNotRead() throws IOException {
        try (TFileChannel ch = TFileChannel.open(dir, READ)) {
            ch.force(true);
            assertThatThrownBy(() -> ch.read(ByteBuffer.allocate(1)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Is a directory");
        }
    }

    @Test
    void createsKeepsTruncatesAndAppends() throws IOException {
        Path created = dir.resolve("new");
        try (TFileChannel ch = TFileChannel.open(created, WRITE, CREATE_NEW)) {
            ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }
        assertThat(Files.readAllBytes(created)).containsExactly(1, 2, 3);

        try (TFileChannel ch = TFileChannel.open(created, READ, WRITE, CREATE, DSYNC)) {
            assertThat(ch.size()).isEqualTo(3);
            ch.write(ByteBuffer.wrap(new byte[] {9}));
        }
        assertThat(Files.readAllBytes(created)).containsExactly(9, 2, 3);

        try (TFileChannel ch = TFileChannel.open(created, APPEND)) {
            assertThat(ch.position()).isEqualTo(3);
            ch.position(0);
            assertThat(ch.position()).isEqualTo(3);
            ch.write(ByteBuffer.wrap(new byte[] {4}));
            assertThat(ch.position()).isEqualTo(4);
            assertThatThrownBy(() -> ch.read(ByteBuffer.allocate(1))).isInstanceOf(NonReadableChannelException.class);
        }
        assertThat(Files.readAllBytes(created)).containsExactly(9, 2, 3, 4);

        try (TFileChannel ch = TFileChannel.open(created, WRITE, TRUNCATE_EXISTING)) {
            assertThat(ch.size()).isZero();
        }
        try (TFileChannel ch = TFileChannel.open(created, READ, TRUNCATE_EXISTING)) {
            assertThat(ch.isOpen()).isTrue(); // Ignored without WRITE, as in the JDK.
        }
    }

    @Test
    void deletesOnClose() throws IOException {
        Path path = file("temp", 1);
        TFileChannel ch = TFileChannel.open(path, READ, WRITE, DELETE_ON_CLOSE);
        assertThat(path).exists();
        ch.close();
        assertThat(path).doesNotExist();
    }

    // --- Reading and writing -----------------------------------------------------------------------

    /** Runs the same operations on a JDK channel and ours, and records what each returns. */
    private List<Object> script(SeekableByteChannel ch) throws IOException {
        List<Object> trace = new ArrayList<>();
        trace.add(ch.write(ByteBuffer.wrap(new byte[] {10, 20, 30, 40, 50})));
        trace.add(ch.position());
        trace.add(ch.position(1).position());
        ByteBuffer two = ByteBuffer.allocate(2);
        trace.add(ch.read(two));
        trace.add(List.of(two.get(0), two.get(1)));
        trace.add(ch.read(ByteBuffer.allocate(0)));
        trace.add(ch.position(5).read(ByteBuffer.allocate(4)));
        trace.add(ch.position(100).read(ByteBuffer.allocate(0)));
        trace.add(ch.read(ByteBuffer.allocate(4)));
        trace.add(ch.position(8).write(ByteBuffer.wrap(new byte[] {7})));
        trace.add(ch.size());
        ByteBuffer direct = ByteBuffer.allocateDirect(9);
        trace.add(ch.position(0).read(direct));
        trace.add(direct.flip().toString() + direct.get(8));
        trace.add(ch.position(20).truncate(6).position());
        trace.add(ch.size());
        trace.add(ch.truncate(50).size());
        trace.add(ch.position(2).truncate(4).position());
        direct.clear().put(new byte[] {1, 2, 3}).flip();
        trace.add(ch.write(direct));
        trace.add(ch.size());
        trace.add(thrown(() -> ch.position(-1)));
        return trace;
    }

    @Test
    void readsWritesAndMovesLikeTheJdk() throws IOException {
        List<Object> jdk;
        try (FileChannel ch = FileChannel.open(dir.resolve("jdk"), READ, WRITE, CREATE)) {
            jdk = script(ch);
        }
        List<Object> ours;
        try (TFileChannel ch = TFileChannel.open(dir.resolve("ours"), READ, WRITE, CREATE)) {
            ours = script(ch);
        }
        assertThat(ours).isEqualTo(jdk);
        assertThat(Files.readAllBytes(dir.resolve("ours"))).isEqualTo(Files.readAllBytes(dir.resolve("jdk")));
    }

    @Test
    void positionalReadsAndWritesLeaveThePositionAlone() throws IOException {
        Path path = file("a", 1, 2, 3, 4);
        try (TFileChannel ch = TFileChannel.open(path, READ, WRITE)) {
            ch.position(1);
            ByteBuffer buffer = ByteBuffer.allocate(2);
            assertThat(ch.read(buffer, 2)).isEqualTo(2);
            assertThat(buffer.array()).containsExactly(3, 4);
            assertThat(ch.read(ByteBuffer.allocate(1), 4)).isEqualTo(-1);
            assertThat(ch.read(ByteBuffer.allocate(0), 10)).isZero();
            assertThat(ch.write(ByteBuffer.wrap(new byte[] {8, 9}), 5)).isEqualTo(2);
            assertThat(ch.position()).isEqualTo(1);
            assertThat(ch.size()).isEqualTo(7);
            assertThatThrownBy(() -> ch.read(ByteBuffer.allocate(1), -1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ch.read(ByteBuffer.allocate(1).asReadOnlyBuffer()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Read-only buffer");
        }
        byte[] content = Files.readAllBytes(path);
        assertThat(content).startsWith(1, 2, 3, 4).endsWith(8, 9);
    }

    @Test
    void scattersAndGathersLikeTheJdk() throws IOException {
        byte[][] results = new byte[2][];
        long[][] counts = new long[2][];
        for (int i = 0; i < 2; i++) {
            Path path = dir.resolve("f" + i);
            SeekableByteChannel ch =
                    i == 0 ? FileChannel.open(path, READ, WRITE, CREATE) : TFileChannel.open(path, READ, WRITE, CREATE);
            try (ch) {
                ByteBuffer[] out = {
                    ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.allocate(0), ByteBuffer.wrap(new byte[] {3})
                };
                long written = i == 0 ? ((FileChannel) ch).write(out) : ((TFileChannel) ch).write(out, 0, 3);
                ch.position(1);
                ByteBuffer[] in = {ByteBuffer.allocate(1), ByteBuffer.allocate(5), ByteBuffer.allocate(1)};
                long read = i == 0 ? ((FileChannel) ch).read(in) : ((TFileChannel) ch).read(in);
                long atEnd = i == 0 ? ((FileChannel) ch).read(in, 0, 1) : ((TFileChannel) ch).read(in, 0, 1);
                in[0].clear();
                long again = i == 0 ? ((FileChannel) ch).read(in, 0, 1) : ((TFileChannel) ch).read(in, 0, 1);
                long none = i == 0 ? ((FileChannel) ch).read(in, 1, 0) : ((TFileChannel) ch).read(in, 1, 0);
                counts[i] = new long[] {written, read, atEnd, again, none, in[1].position(), in[2].position()};
            }
            results[i] = Files.readAllBytes(path);
        }
        assertThat(counts[1]).isEqualTo(counts[0]);
        assertThat(results[1]).isEqualTo(results[0]);
    }

    @Test
    void transfersLikeTheJdk() throws IOException {
        Path path = file("a", 1, 2, 3, 4, 5);
        try (TFileChannel ch = TFileChannel.open(path, READ, WRITE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(ch.transferTo(1, 3, Channels.newChannel(out))).isEqualTo(3);
            assertThat(out.toByteArray()).containsExactly(2, 3, 4);
            assertThat(ch.transferTo(4, 100, Channels.newChannel(out))).isEqualTo(1);
            assertThat(ch.transferTo(9, 1, Channels.newChannel(out))).isZero();
            assertThat(ch.position()).isZero();

            byte[] big = new byte[20_000];
            big[19_999] = 42;
            ByteArrayInputStream in = new ByteArrayInputStream(big);
            assertThat(ch.transferFrom(Channels.newChannel(in), 5, 30_000)).isEqualTo(20_000);
            assertThat(ch.size()).isEqualTo(20_005);
            assertThat(ch.transferFrom(Channels.newChannel(new ByteArrayInputStream(big)), 30_000, 1))
                    .isZero();
            assertThatThrownBy(() -> ch.transferTo(-1, 1, Channels.newChannel(out)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.readAllBytes(path)[20_004]).isEqualTo((byte) 42);
        try (TFileChannel ch = TFileChannel.open(path, READ)) {
            assertThatThrownBy(() -> ch.transferFrom(Channels.newChannel(new ByteArrayInputStream(new byte[1])), 0, 1))
                    .isInstanceOf(NonWritableChannelException.class);
        }
    }

    @Test
    void mapIsUnavailable() throws IOException {
        try (TFileChannel ch = TFileChannel.open(file("a", 1), READ)) {
            assertThatThrownBy(() -> ch.map(TFileChannel.MapMode.READ_ONLY, 0, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(TFileChannel.MapMode.READ_ONLY.toString()).isEqualTo(FileChannel.MapMode.READ_ONLY.toString());
        assertThat(TFileChannel.MapMode.READ_WRITE.toString()).isEqualTo(FileChannel.MapMode.READ_WRITE.toString());
        assertThat(TFileChannel.MapMode.PRIVATE.toString()).isEqualTo(FileChannel.MapMode.PRIVATE.toString());
    }

    // --- Closing -----------------------------------------------------------------------------------

    @Test
    void aClosedChannelRefusesEverything() throws IOException {
        TFileChannel ch = TFileChannel.open(file("a", 1), READ, WRITE);
        ch.close();
        ch.close();
        assertThat(ch.isOpen()).isFalse();
        for (Class<?> failure : List.of(
                thrown(() -> ch.read(ByteBuffer.allocate(1))),
                thrown(() -> ch.write(ByteBuffer.allocate(1))),
                thrown(ch::size),
                thrown(ch::position),
                thrown(() -> ch.position(-1)),
                thrown(() -> ch.truncate(0)),
                thrown(() -> ch.force(false)),
                thrown(() -> ch.lock(-1, 1, false)),
                thrown(ch::tryLock))) {
            assertThat(failure).isEqualTo(ClosedChannelException.class);
        }
    }

    @Test
    void anInterruptedThreadClosesTheChannelLikeTheJdk() throws Exception {
        Path path = file("a", 1, 2);
        List<Object> jdk = interrupted(FileChannel.open(path, READ));
        List<Object> ours = interrupted(TFileChannel.open(path, READ));
        assertThat(jdk.get(0)).isEqualTo(java.nio.channels.ClosedByInterruptException.class);
        assertThat(ours.get(0)).isEqualTo(TClosedByInterruptException.class);
        assertThat(ours.subList(1, ours.size())).isEqualTo(jdk.subList(1, jdk.size()));
    }

    private static List<Object> interrupted(SeekableByteChannel ch) throws Exception {
        List<Object> result = new ArrayList<>();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.add(thrown(() -> ch.read(ByteBuffer.allocate(1))));
            result.add(ch.isOpen());
            result.add(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join();
        result.add(thrown(() -> ch.read(ByteBuffer.allocate(1))));
        return result;
    }

    // --- Locks -------------------------------------------------------------------------------------

    @Test
    void locksRefuseOverlapsAcrossTheProgramLikeTheJdk() throws IOException {
        Path path = file("a", 1, 2, 3);
        TFileChannel other = TFileChannel.open(path, READ, WRITE);
        try (TFileChannel ch = TFileChannel.open(path, READ, WRITE)) {
            TFileLock rest = ch.lock(1, 0, false);
            assertThat(rest.size()).isEqualTo(Long.MAX_VALUE - 1);
            assertThat(rest.channel()).isSameAs(ch);
            assertThat(rest.acquiredBy()).isSameAs(ch);
            assertThatThrownBy(() -> ch.tryLock(5, 1, false)).isInstanceOf(TOverlappingFileLockException.class);
            assertThatThrownBy(() -> other.tryLock(0, 2, true)).isInstanceOf(TOverlappingFileLockException.class);
            TFileLock first = other.tryLock(0, 1, true);
            assertThat(first.isShared()).isTrue();
            rest.release();
            assertThat(rest.isValid()).isFalse();
            rest.release();
            try (TFileLock again = ch.lock(1, 2, false)) {
                assertThat(again.isValid()).isTrue();
                assertThat(again.toString()).endsWith("[1:2 exclusive valid]");
            }
            assertThat(first.toString()).endsWith("[0:1 shared valid]");
            other.close();
            assertThat(first.isValid()).isFalse();
            assertThatThrownBy(first::release).isInstanceOf(ClosedChannelException.class);
            // Closing released the other channel's lock, so its range is free again.
            ch.lock().release();
        }
    }

    @Test
    void lockArgumentsAndModesLikeTheJdk() throws IOException {
        Path path = file("a", 1);
        try (FileChannel jdk = FileChannel.open(path, READ);
                TFileChannel ours = TFileChannel.open(path, READ)) {
            assertThat(thrown(ours::lock)).isEqualTo(thrown(jdk::lock));
            assertThat(thrown(() -> ours.lock(-1, 1, true))).isEqualTo(thrown(() -> jdk.lock(-1, 1, true)));
            assertThat(thrown(() -> ours.lock(1, -1, true))).isEqualTo(thrown(() -> jdk.lock(1, -1, true)));
            assertThat(thrown(() -> ours.lock(Long.MAX_VALUE, 1, true)))
                    .isEqualTo(thrown(() -> jdk.lock(Long.MAX_VALUE, 1, true)));
            ours.lock(0, 1, true).release();
        }
        try (TFileChannel ours = TFileChannel.open(path, APPEND)) {
            assertThat(thrown(() -> ours.lock(0, 1, true))).isEqualTo(NonReadableChannelException.class);
            ours.lock().release();
        }
    }

    @Test
    void overlapsLikeTheJdk() throws IOException {
        Path path = file("a", 1);
        long[][] queries = {
            {0, 0},
            {1, 0},
            {2, 0},
            {3, 0},
            {-5, 6},
            {-5, 5},
            {0, -1},
            {3, 1},
            {4, 1},
            {0, Long.MAX_VALUE},
            {Long.MAX_VALUE, Long.MAX_VALUE},
            {Long.MIN_VALUE, Long.MAX_VALUE}
        };
        try (FileChannel jdk = FileChannel.open(path, READ, WRITE);
                TFileChannel ours = TFileChannel.open(dir.resolve("b"), READ, WRITE, CREATE)) {
            for (long[] range : new long[][] {{2, 2}, {0, Long.MAX_VALUE}}) {
                try (java.nio.channels.FileLock jdkLock = jdk.lock(range[0], range[1], false);
                        TFileLock ourLock = ours.lock(range[0], range[1], false)) {
                    for (long[] query : queries) {
                        assertThat(ourLock.overlaps(query[0], query[1]))
                                .as("%s overlaps %s", ourLock, List.of(query[0], query[1]))
                                .isEqualTo(jdkLock.overlaps(query[0], query[1]));
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    static Class<?> thrown(Action action) {
        try {
            action.run();
            return Void.class;
        } catch (Exception e) {
            return e.getClass();
        }
    }
}
