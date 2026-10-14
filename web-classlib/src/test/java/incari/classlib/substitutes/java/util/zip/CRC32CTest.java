package incari.classlib.substitutes.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class CRC32CTest {

    @Test
    void matchesTheJdk() {
        byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        CRC32C jdk = new CRC32C();
        jdk.update(data, 3, 20);
        jdk.update(0xFF);
        TCRC32C ours = new TCRC32C();
        ours.update(data, 3, 20);
        ours.update(0xFF);
        assertThat(ours.getValue()).isEqualTo(jdk.getValue());
        ours.reset();
        assertThat(ours.getValue()).isEqualTo(new CRC32C().getValue());
    }
}
