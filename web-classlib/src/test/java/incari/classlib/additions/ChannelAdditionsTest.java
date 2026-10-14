package incari.classlib.additions;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.additions.java.io.FileInputStreamAdditions;
import incari.classlib.additions.java.io.FileOutputStreamAdditions;
import incari.classlib.additions.java.io.RandomAccessFileAdditions;
import incari.classlib.additions.java.nio.file.FilesAdditions;
import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import incari.classlib.substitutes.java.nio.channels.TFileLock;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code getChannel} and {@code Files.newByteChannel} additions, against the JDK's. */
class ChannelAdditionsTest {

    @TempDir
    Path dir;

    @Test
    void aRandomAccessFilesChannelSharesItsPointerLikeTheJdk() throws IOException {
        Path path = Files.write(dir.resolve("a"), new byte[] {1, 2, 3, 4, 5});
        for (boolean jdk : new boolean[] {true, false}) {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                SeekableByteChannel ch = jdk ? file.getChannel() : RandomAccessFileAdditions.getChannel(file);
                assertThat(jdk ? file.getChannel() : RandomAccessFileAdditions.getChannel(file))
                        .isSameAs(ch);
                file.seek(2);
                assertThat(ch.position()).isEqualTo(2);
                ByteBuffer buffer = ByteBuffer.allocate(2);
                ch.read(buffer);
                assertThat(buffer.array()).containsExactly(3, 4);
                assertThat(file.getFilePointer()).isEqualTo(4);
                ch.position(1);
                assertThat(file.read()).isEqualTo(2);
                ch.close();
                assertThatThrownBy(file::read).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void aFileInputStreamsChannelReadsThroughTheStream() throws IOException {
        Path path = Files.write(dir.resolve("a"), new byte[] {1, 2, 3, 4, 5});
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            TFileChannel ch = FileInputStreamAdditions.getChannel(in);
            assertThat(FileInputStreamAdditions.getChannel(in)).isSameAs(ch);
            assertThat(in.read()).isEqualTo(1);
            ByteBuffer buffer = ByteBuffer.allocate(2);
            assertThat(ch.read(buffer)).isEqualTo(2);
            assertThat(buffer.array()).containsExactly(2, 3);
            assertThat(in.read()).isEqualTo(4);
            // A deviation: TeaVM's stream hides its file, so this channel can't seek or size it.
            assertThatThrownBy(ch::position).isInstanceOf(IOException.class);
            assertThatThrownBy(ch::size).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> ch.write(ByteBuffer.allocate(1))).isInstanceOf(NonWritableChannelException.class);
            TFileLock lock = ch.lock(0, 1, true);
            assertThat(lock.isValid()).isTrue();
            ch.close();
            assertThat(lock.isValid()).isFalse();
            assertThatThrownBy(in::read).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aFileOutputStreamsChannelWritesThroughTheStreamAndLocks() throws IOException {
        Path path = dir.resolve("lock");
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            TFileChannel ch = FileOutputStreamAdditions.getChannel(out);
            out.write(1);
            ch.write(ByteBuffer.wrap(new byte[] {2, 3}));
            out.write(4);
            ch.force(true);
            TFileLock lock = ch.tryLock();
            assertThat(lock.isValid()).isTrue();
            lock.release();
        }
        assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3, 4);
    }

    @Test
    void newByteChannelOpensAFileChannel() throws IOException {
        Path path = dir.resolve("a");
        try (SeekableByteChannel ch = FilesAdditions.newByteChannel(path, WRITE, CREATE)) {
            assertThat(ch).isInstanceOf(TFileChannel.class);
            ch.write(ByteBuffer.wrap(new byte[] {7, 8}));
        }
        try (SeekableByteChannel ch = FilesAdditions.newByteChannel(path, Set.of(READ))) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            assertThat(ch.read(buffer)).isEqualTo(2);
            assertThat(ch.size()).isEqualTo(2);
        }
    }
}
