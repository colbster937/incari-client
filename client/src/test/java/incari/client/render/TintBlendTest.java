package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.TestPlatform;
import java.util.Random;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link TintBlend} blends as the game's {@code ClientLevel.calculateBlockTint}, reused samples or not. */
class TintBlendTest {

    private static final ColorResolver GRASS = (biome, x, z) -> 1;
    private static final ColorResolver FOLIAGE = (biome, x, z) -> 2;

    @BeforeAll
    static void platform() {
        // Its block position class starts the game's background pool, which asks the platform.
        TestPlatform.install();
    }

    @Test
    void blendsAsTheGameDoesAlongRowsAndAcrossThem() {
        Random random = new Random(20261001);
        // Colours that depend on the position and the resolver, as swamp grass's do.
        TintBlend.ColorAt grass = (x, y, z) -> hash(x, y, z, 1);
        TintBlend.ColorAt foliage = (x, y, z) -> hash(x, y, z, 2);
        int x = 0;
        int y = 64;
        int z = 0;
        for (int i = 0; i < 20000; i++) {
            switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> x++;
                case 4 -> x += random.nextInt(6);
                case 5 -> x -= random.nextInt(3);
                case 6 -> z += random.nextInt(3) - 1;
                case 7 -> y += random.nextInt(3) - 1;
                case 8 -> TintBlend.changed();
                default -> x = random.nextInt(64) - 32;
            }
            int radius = random.nextInt(20) == 0 ? random.nextInt(8) + 1 : 2;
            boolean useFoliage = random.nextInt(5) == 0;
            ColorResolver resolver = useFoliage ? FOLIAGE : GRASS;
            TintBlend.ColorAt colors = useFoliage ? foliage : grass;
            assertThat(TintBlend.blend(x, y, z, resolver, radius, colors))
                    .as("step %d at %d %d %d, radius %d", i, x, y, z, radius)
                    .isEqualTo(gameBlend(x, y, z, radius, colors));
        }
    }

    @Test
    void forgetsSamplesWhenTheBiomesChange() {
        int[] shift = {0};
        TintBlend.ColorAt colors = (x, y, z) -> hash(x, y, z, 3) + shift[0];
        int before = TintBlend.blend(5, 70, 5, GRASS, 2, colors);
        assertThat(before).isEqualTo(gameBlend(5, 70, 5, 2, colors));
        shift[0] = 0x010101;
        TintBlend.changed();
        assertThat(TintBlend.blend(6, 70, 5, GRASS, 2, colors)).isEqualTo(gameBlend(6, 70, 5, 2, colors));
    }

    /** The game's own loop, as written in {@code ClientLevel.calculateBlockTint}. */
    private static int gameBlend(int x, int y, int z, int radius, TintBlend.ColorAt colors) {
        int count = (radius * 2 + 1) * (radius * 2 + 1);
        int totalRed = 0;
        int totalGreen = 0;
        int totalBlue = 0;
        for (int sz = z - radius; sz <= z + radius; sz++) {
            for (int sx = x - radius; sx <= x + radius; sx++) {
                int color = colors.color(sx, y, sz);
                totalRed += ARGB.red(color);
                totalGreen += ARGB.green(color);
                totalBlue += ARGB.blue(color);
            }
        }
        return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
    }

    private static int hash(int x, int y, int z, int salt) {
        int h = (x * 31 + y) * 31 + z + salt * 1000003;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return 0xFF000000 | (h & 0xFFFFFF);
    }
}
