package incari.classlib.substitutes.java.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks {@link TUUID} against the JDK's {@link UUID}. */
class UUIDTest {

    @Test
    void nameBasedMatchesTheJdk() {
        for (String name : new String[] {"", "OfflinePlayer:Player", "OfflinePlayer:Notch", "x".repeat(200)}) {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            assertThat(TUUID.nameUUIDFromBytes(bytes).toString())
                    .isEqualTo(UUID.nameUUIDFromBytes(bytes).toString());
        }
    }

    @Test
    void roundTripsThroughText() {
        UUID jdk = UUID.randomUUID();
        TUUID ours = TUUID.fromString(jdk.toString());
        assertThat(ours.getMostSignificantBits()).isEqualTo(jdk.getMostSignificantBits());
        assertThat(ours.getLeastSignificantBits()).isEqualTo(jdk.getLeastSignificantBits());
        assertThat(ours.toString()).isEqualTo(jdk.toString());
        assertThat(ours.hashCode()).isEqualTo(jdk.hashCode());
        assertThat(TUUID.fromString("1-2-3-4-5").toString())
                .isEqualTo(UUID.fromString("1-2-3-4-5").toString());
    }

    @Test
    void randomIsVersionFour() {
        TUUID uuid = TUUID.randomUUID();
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid).isNotEqualTo(TUUID.randomUUID());
    }

    @Test
    void comparesLikeTheJdk() {
        UUID a = new UUID(-1, 5);
        UUID b = new UUID(1, 5);
        assertThat(Integer.signum(new TUUID(-1, 5).compareTo(new TUUID(1, 5))))
                .isEqualTo(Integer.signum(a.compareTo(b)));
    }
}
