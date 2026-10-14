package incari.classlib.substitutes.java.nio.channels;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TChannels} against the JDK's {@link Channels}. */
class ChannelsTest {

    private static final byte[] DATA = "héllo, channels".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void streamsOverAFileChannelLikeTheJdk() throws IOException {
        Path path = Files.write(dir.resolve("a"), DATA);
        try (TFileChannel ch = TFileChannel.open(path, READ);
                InputStream in = TChannels.newInputStream(ch)) {
            assertThat(in.available()).isEqualTo(DATA.length);
            assertThat(in.read()).isEqualTo(DATA[0] & 0xFF);
            assertThat(in.skip(2)).isEqualTo(2);
            assertThat(ch.position()).isEqualTo(3);
            assertThat(in.readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(DATA, 3, DATA.length));
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.skip(5)).isZero();
            assertThat(in.available()).isZero();
        }
        Path out = dir.resolve("b");
        try (TFileChannel ch = TFileChannel.open(out, WRITE, CREATE);
                OutputStream stream = TChannels.newOutputStream(ch)) {
            stream.write(DATA[0]);
            stream.write(DATA, 1, DATA.length - 1);
        }
        assertThat(Files.readAllBytes(out)).isEqualTo(DATA);
    }

    @Test
    void closingTheStreamClosesTheChannel() throws IOException {
        TFileChannel ch = TFileChannel.open(Files.write(dir.resolve("a"), DATA), READ);
        TChannels.newInputStream(ch).close();
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void channelsOverStreamsLikeTheJdk() throws IOException {
        assertThat(readAll(TChannels.newChannel(new ByteArrayInputStream(DATA)), 4))
                .isEqualTo(readAll(Channels.newChannel(new ByteArrayInputStream(DATA)), 4));

        ReadableByteChannel ours = TChannels.newChannel(new ByteArrayInputStream(DATA));
        assertThat(ours.read(ByteBuffer.allocate(0))).isZero();
        ours.close();
        assertThat(ours.isOpen()).isFalse();
        assertThatThrownBy(() -> ours.read(ByteBuffer.allocate(1))).isInstanceOf(ClosedChannelException.class);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        WritableByteChannel out = TChannels.newChannel(sink);
        ByteBuffer direct = ByteBuffer.allocateDirect(DATA.length).put(DATA).flip();
        assertThat(out.write(direct)).isEqualTo(DATA.length);
        assertThat(direct.hasRemaining()).isFalse();
        assertThat(sink.toByteArray()).isEqualTo(DATA);
        out.close();
        assertThatThrownBy(() -> out.write(ByteBuffer.allocate(1))).isInstanceOf(ClosedChannelException.class);
    }

    private static String readAll(ReadableByteChannel ch, int chunk) throws IOException {
        StringBuilder trace = new StringBuilder();
        ByteBuffer buffer = ByteBuffer.allocate(chunk);
        int n;
        while ((n = ch.read(buffer.clear())) >= 0) {
            trace.append(n).append(':').append(new String(buffer.array(), 0, n, StandardCharsets.ISO_8859_1));
            trace.append('|');
        }
        return trace.append(n).toString();
    }

    @Test
    void readersAndWritersLikeTheJdk() throws IOException {
        String text = "héllo ✓ world";
        ByteArrayOutputStream ours = new ByteArrayOutputStream();
        try (Writer writer = TChannels.newWriter(TChannels.newChannel(ours), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        ByteArrayOutputStream jdk = new ByteArrayOutputStream();
        try (Writer writer = Channels.newWriter(Channels.newChannel(jdk), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        assertThat(ours.toByteArray()).isEqualTo(jdk.toByteArray());

        try (Writer writer = TChannels.newWriter(
                TChannels.newChannel(new ByteArrayOutputStream()), StandardCharsets.UTF_16.newEncoder(), -1)) {
            writer.write(text);
        }
        for (Reader reader : new Reader[] {
            TChannels.newReader(TChannels.newChannel(new ByteArrayInputStream(jdk.toByteArray())), "UTF-8"),
            TChannels.newReader(
                    TChannels.newChannel(new ByteArrayInputStream(jdk.toByteArray())), StandardCharsets.UTF_8),
            TChannels.newReader(
                    TChannels.newChannel(new ByteArrayInputStream(jdk.toByteArray())),
                    StandardCharsets.UTF_8.newDecoder(),
                    -1)
        }) {
            try (reader) {
                char[] chars = new char[64];
                int n = reader.read(chars);
                assertThat(new String(chars, 0, n)).isEqualTo(text);
            }
        }
        assertThatThrownBy(() -> TChannels.newReader(TChannels.newChannel(InputStream.nullInputStream()), "no-such"))
                .isInstanceOf(UnsupportedCharsetException.class);
    }
}
