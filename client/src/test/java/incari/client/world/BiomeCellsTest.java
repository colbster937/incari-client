package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.VanillaRegistries;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every block's biome from {@link BiomeCells} is the game's blurred one, over biome cells laid out
 * in large patches with single odd cells among them, for whole chunks and their surroundings.
 */
class BiomeCellsTest {

    private static List<Holder<Biome>> biomes;

    @BeforeAll
    static void bootstrap() {
        Registry<Biome> registry = VanillaRegistries.load().lookupOrThrow(Registries.BIOME);
        biomes = List.of(
                registry.getOrThrow(Biomes.PLAINS),
                registry.getOrThrow(Biomes.FOREST),
                registry.getOrThrow(Biomes.DESERT),
                registry.getOrThrow(Biomes.OCEAN),
                registry.getOrThrow(Biomes.DRIPSTONE_CAVES));
    }

    @AfterEach
    void off() {
        BiomeCells.setEnabled(false);
    }

    @ParameterizedTest
    @ValueSource(longs = {20260928L, -4172144997902289642L})
    void everyBlocksBiomeIsTheGames(long seed) {
        // Patches of 8x4x8 cells, with a twentieth of the cells something else.
        BiomeResolver cells = (x, y, z) -> {
            int odd = Math.floorMod((x * 73_856_093) ^ (y * 19_349_663) ^ (z * 83_492_791), 20);
            int patch = Math.floorMod((x >> 3) * 31 + (y >> 2) * 17 + (z >> 3) * 7, biomes.size());
            return biomes.get(odd == 0 ? (patch + 1) % biomes.size() : patch);
        };
        BiomeManager manager = new BiomeManager(cells, BiomeManager.obfuscateSeed(seed));

        BiomeCells.setEnabled(true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int chunkX = -2; chunkX <= 2; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                int minX = chunkX * 16;
                int minZ = chunkZ * 16;
                Function<BlockPos, Holder<Biome>> lookup = BiomeCells.forChunk(manager, minX, -64, minZ, 319);
                assertThat(lookup).isInstanceOf(BiomeCells.class);
                // The chunk and two blocks around it, which fall outside its groups.
                for (int y = 330; y >= -70; y--) {
                    for (int x = minX - 2; x < minX + 18; x++) {
                        for (int z = minZ - 2; z < minZ + 18; z++) {
                            pos.set(x, y, z);
                            assertThat(lookup.apply(pos)).as("%s", pos).isSameAs(manager.getBiome(pos));
                        }
                    }
                }
            }
        }
    }
}
