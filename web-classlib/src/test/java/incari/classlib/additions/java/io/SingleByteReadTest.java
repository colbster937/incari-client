package incari.classlib.additions.java.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The single-byte reads that replace TeaVM's, against the JDK's on the same file. */
class SingleByteReadTest {

    @TempDir
    Path directory;

    @Test
    void bytesReadUnsignedAndTheEndReadsMinusOne() throws IOException {
        Path file = directory.resolve("servers.dat");
        Files.write(file, new byte[] {0x0A, (byte) 0xC8, (byte) 0xFF, 0});
        try (FileInputStream jdk = new FileInputStream(file.toFile());
                FileInputStream ours = new FileInputStream(file.toFile())) {
            for (int i = 0; i < 5; i++) {
                assertThat(FileInputStreamAdditions.read(ours)).isEqualTo(jdk.read());
            }
        }
        try (RandomAccessFile jdk = new RandomAccessFile(file.toFile(), "r");
                RandomAccessFile ours = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = 0; i < 5; i++) {
                assertThat(RandomAccessFileAdditions.read(ours)).isEqualTo(jdk.read());
            }
        }
    }
}
