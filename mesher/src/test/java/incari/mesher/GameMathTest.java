package incari.mesher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The mesher's copies of the game's arithmetic. The game itself checks them against its own
 * before meshing; here, what can be checked without it.
 */
class GameMathTest {

    @Test
    void theRandomIsJavasGenerator() {
        java.util.Random reference = new java.util.Random();
        GameMath.Random ours = new GameMath.Random();
        for (long seed = -500; seed < 500; seed += 7) {
            reference.setSeed(seed * 0x9E3779B97F4A7C15L);
            ours.setSeed(seed * 0x9E3779B97F4A7C15L);
            for (int bound : new int[] {1, 2, 3, 7, 16, 64, 100, 1 << 20, 1_000_003}) {
                assertThat(ours.nextInt(bound)).isEqualTo(reference.nextInt(bound));
            }
            assertThat(ours.nextLong()).isEqualTo(reference.nextLong());
        }
    }

    @Test
    void theSineIsATableOfSixtyFiveThousandSteps() {
        assertThat(GameMath.sin(0.0)).isEqualTo(0.0F);
        assertThat(GameMath.cos(0.0)).isEqualTo(1.0F);
        assertThat(GameMath.sin(Math.PI / 2)).isEqualTo(1.0F);
        // Within a step of the true value everywhere.
        for (double a = -10.0; a < 10.0; a += 0.01) {
            assertThat((double) GameMath.sin(a)).isCloseTo(Math.sin(a), org.assertj.core.data.Offset.offset(2e-4));
        }
    }

    @Test
    void theArctangentIsCloseToTheTrueOne() {
        for (double y = -1.0; y <= 1.0; y += 0.05) {
            for (double x = -1.0; x <= 1.0; x += 0.05) {
                if (Math.abs(x) + Math.abs(y) > 1e-6) {
                    assertThat(GameMath.atan2(y, x))
                            .isCloseTo(Math.atan2(y, x), org.assertj.core.data.Offset.offset(1e-5));
                }
            }
        }
    }

    @Test
    void positionSeedsDifferByPosition() {
        assertThat(GameMath.positionSeed(0, 0, 0)).isZero();
        assertThat(GameMath.positionSeed(1, 0, 0)).isNotEqualTo(GameMath.positionSeed(0, 0, 1));
    }
}
