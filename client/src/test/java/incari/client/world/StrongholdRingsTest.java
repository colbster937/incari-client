package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.VanillaRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The strongholds worked out ring by ring are the game's own, on the vanilla overworld: the same
 * positions in the same order, and a chunk is one exactly when the game's list holds it.
 */
class StrongholdRingsTest {

    /** Runs nothing: the searches happen only when the test asks. */
    private static final Executor NOWHERE = task -> {};

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() {
        registries = VanillaRegistries.load();
    }

    @AfterEach
    void off() {
        StrongholdRings.setEnabled(false);
    }

    @ParameterizedTest
    @ValueSource(longs = {20260928L, -4172144997902289642L})
    void ringsAreTheGamesInTheGamesOrder(long seed) {
        World world = new World(seed);
        List<ChunkPos> game = world.gameRings();

        StrongholdRings.Rings rings = world.lazyRings();
        assertThat(rings.searched()).isZero();
        assertThat(new ArrayList<>(rings)).isEqualTo(game);
        assertThat(rings.searched()).isEqualTo(game.size());
    }

    @ParameterizedTest
    @ValueSource(longs = {20260928L, -4172144997902289642L})
    void aChunkIsARingPositionExactlyWhenTheGameSaysSo(long seed) {
        World world = new World(seed);
        List<ChunkPos> game = world.gameRings();

        StrongholdRings.Rings rings = world.lazyRings();
        // Spawn's surroundings are far inside the first ring: nothing to search.
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                assertThat(rings.contains(new ChunkPos(x, z))).isFalse();
            }
        }
        assertThat(rings.searched()).isZero();

        for (ChunkPos ring : game.subList(0, 12)) {
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    ChunkPos chunk = new ChunkPos(ring.x() + dx, ring.z() + dz);
                    assertThat(rings.contains(chunk)).as("%s", chunk).isEqualTo(game.contains(chunk));
                }
            }
        }
    }

    /** A vanilla overworld's biomes and noise for {@code seed}. */
    private static final class World {
        private final long seed;
        private final BiomeSource biomes;
        private final RandomState randomState;
        private final ConcentricRingsStructurePlacement placement;

        World(long seed) {
            this.seed = seed;
            biomes = MultiNoiseBiomeSource.createFromPreset(registries
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            NoiseGeneratorSettings settings = registries
                    .lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD)
                    .value();
            randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
            placement = (ConcentricRingsStructurePlacement) registries
                    .lookupOrThrow(Registries.STRUCTURE_SET)
                    .getOrThrow(BuiltinStructureSets.STRONGHOLDS)
                    .value()
                    .placement();
        }

        List<ChunkPos> gameRings() {
            StrongholdRings.setEnabled(false);
            ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(
                    randomState, seed, ChunkPos.ZERO, biomes, registries.lookupOrThrow(Registries.STRUCTURE_SET));
            List<ChunkPos> rings = state.getRingPositionsFor(placement);
            assertThat(rings).hasSize(placement.count());
            return List.copyOf(rings);
        }

        StrongholdRings.Rings lazyRings() {
            StrongholdRings.setEnabled(true);
            var future = StrongholdRings.plan(placement, seed, biomes, randomState, NOWHERE);
            assertThat(future).isNotNull();
            return (StrongholdRings.Rings) future.join();
        }
    }
}
