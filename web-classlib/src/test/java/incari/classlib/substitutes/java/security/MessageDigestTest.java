package incari.classlib.substitutes.java.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The stand-in against the JDK's digests, over lengths around every block boundary. */
class MessageDigestTest {

    @Test
    void hashesLikeTheJdk() throws Exception {
        Random random = new Random(11);
        for (String algorithm : new String[] {"MD5", "SHA-1", "SHA-256"}) {
            for (int length = 0; length < 300; length++) {
                byte[] input = new byte[length];
                random.nextBytes(input);
                TMessageDigest ours = TMessageDigest.getInstance(algorithm);
                MessageDigest jdk = MessageDigest.getInstance(algorithm);
                // In pieces: a byte, a range, a buffer.
                int split = length / 3;
                if (length > 0) {
                    ours.update(input[0]);
                    jdk.update(input[0]);
                }
                int from = Math.min(1, length);
                ours.update(input, from, Math.max(0, split - from));
                jdk.update(input, from, Math.max(0, split - from));
                int rest = Math.max(split, from);
                ours.update(ByteBuffer.wrap(input, rest, length - rest));
                jdk.update(ByteBuffer.wrap(input, rest, length - rest));
                assertThat(ours.digest())
                        .as("%s of %d bytes", algorithm, length)
                        .isEqualTo(jdk.digest());
            }
            assertThat(TMessageDigest.getInstance(algorithm).getDigestLength())
                    .isEqualTo(MessageDigest.getInstance(algorithm).getDigestLength());
        }
    }

    @Test
    void aCloneCarriesOnFromTheSameState() throws Exception {
        TMessageDigest digest = TMessageDigest.getInstance("SHA-1");
        digest.update(new byte[] {1, 2, 3});
        TMessageDigest copy = (TMessageDigest) digest.clone();
        digest.update(new byte[] {4});
        copy.update(new byte[] {4});
        assertThat(copy.digest()).isEqualTo(digest.digest());
        assertThat(digest.digest()).isEqualTo(MessageDigest.getInstance("SHA-1").digest());
    }

    @Test
    void otherAlgorithmsAreRefused() {
        assertThatThrownBy(() -> TMessageDigest.getInstance("MD2")).isInstanceOf(NoSuchAlgorithmException.class);
    }
}
