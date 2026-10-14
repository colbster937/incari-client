package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.TestPlatform;
import java.util.Random;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link PositionalRandoms}' first draws are the ones the game's randoms would make, bit for bit. */
class PositionalRandomsTest {

    @BeforeAll
    static void platform() {
        // The game's math and utility classes start the platform's services when first loaded.
        TestPlatform.install();
    }

    @AfterEach
    void off() {
        PositionalRandoms.setEnabled(false);
    }

    @Test
    void firstDrawsAreTheGamesAtEveryPosition() {
        PositionalRandoms.setEnabled(true);
        Random seeds = new Random(26_3);
        for (int factories = 0; factories < 50; factories++) {
            PositionalRandomFactory factory = new XoroshiroRandomSource(seeds.nextLong()).forkPositional();
            for (int i = 0; i < 2000; i++) {
                int x = seeds.nextInt(60_000_000) - 30_000_000;
                int y = seeds.nextInt(4064) - 2032;
                int z = seeds.nextInt(60_000_000) - 30_000_000;
                assertThat(Float.floatToRawIntBits(PositionalRandoms.nextFloatAt(factory, x, y, z)))
                        .isEqualTo(Float.floatToRawIntBits(factory.at(x, y, z).nextFloat()));
                assertThat(Double.doubleToRawLongBits(PositionalRandoms.nextDoubleAt(factory, x, y, z)))
                        .isEqualTo(
                                Double.doubleToRawLongBits(factory.at(x, y, z).nextDouble()));
            }
        }
    }

    @Test
    void anAllZeroSeedTakesTheGeneratorsStandIn() {
        // A factory whose seeds cancel the position's out at (0, 0, 0): seedLo 0 there, seedHi 0.
        PositionalRandoms.setEnabled(true);
        PositionalRandomFactory factory = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(0L, 0L);
        assertThat(PositionalRandoms.nextFloatAt(factory, 0, 0, 0))
                .isEqualTo(factory.at(0, 0, 0).nextFloat());
        assertThat(PositionalRandoms.nextDoubleAt(factory, 0, 0, 0))
                .isEqualTo(factory.at(0, 0, 0).nextDouble());
    }

    @Test
    void otherFactoriesMakeTheirRandom() {
        PositionalRandoms.setEnabled(true);
        PositionalRandomFactory factory = new LegacyRandomSource(42L).forkPositional();
        assertThat(PositionalRandoms.nextFloatAt(factory, 5, 6, 7))
                .isEqualTo(factory.at(5, 6, 7).nextFloat());
    }
}
