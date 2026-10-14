package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.VanillaRegistries;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The vanilla overworld's terrain step (biomes, then noise, surface and carvers) leaves every
 * block and heightmap of a chunk the same with ADR 0041's to 0043's changes as without them: the
 * surface writer, the biome cells, the cached heights, the density functions' lazy range choices,
 * the positional randoms' first draws and the surface's reused density buffers, on
 * areas of three worlds.
 *
 * <p>A whole world can't be compared this way between two runs of the server: the features one
 * chunk places in its neighbours land in the order chunks happen to generate, which differs from
 * run to run even without any change. The terrain step reads nothing but its own chunk and the
 * biomes around it, so here each chunk is generated alone, twice. No structures: the structure
 * manager here has none, the same both ways.
 */
class TerrainEquivalenceTest {

    private static RegistryAccess.Frozen registries;
    private static PalettedContainerFactory containers;

    @BeforeAll
    static void bootstrap() {
        registries = VanillaRegistries.load();
        containers = PalettedContainerFactory.create(registries);
    }

    @AfterEach
    void off() {
        changes(false);
    }

    @ParameterizedTest
    @CsvSource({"20260928, 0, 0", "20260928, 37, -112", "-4172144997902289642, -300, 40", "1, 1000, 1000"})
    void terrainIsTheSameWithTheChangesAsWithout(long seed, int chunkX, int chunkZ) {
        World world = new World(seed);
        changes(false);
        Map<ChunkPos, ProtoChunk> game = world.terrain(chunkX, chunkZ);
        changes(true);
        Map<ChunkPos, ProtoChunk> ours = world.terrain(chunkX, chunkZ);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long blocks = 0;
        long solid = 0;
        for (Map.Entry<ChunkPos, ProtoChunk> entry : game.entrySet()) {
            ProtoChunk expected = entry.getValue();
            ProtoChunk actual = ours.get(entry.getKey());
            for (int y = expected.getMinY(); y <= expected.getMaxY(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(
                                entry.getKey().getMinBlockX() + x,
                                y,
                                entry.getKey().getMinBlockZ() + z);
                        if (!actual.getBlockState(pos).equals(expected.getBlockState(pos))) {
                            assertThat(actual.getBlockState(pos)).as("%s", pos).isEqualTo(expected.getBlockState(pos));
                        }
                        blocks++;
                        if (!expected.getBlockState(pos).isAir()) {
                            solid++;
                        }
                    }
                }
            }
            for (Heightmap.Types type : Heightmap.Types.values()) {
                if (expected.hasPrimedHeightmap(type)) {
                    assertThat(actual.getOrCreateHeightmapUnprimed(type).getRawData())
                            .as("%s %s", entry.getKey(), type)
                            .isEqualTo(
                                    expected.getOrCreateHeightmapUnprimed(type).getRawData());
                }
            }
        }
        assertThat(blocks).isEqualTo(384L * 256 * game.size());
        assertThat(solid).as("terrain was generated").isGreaterThan(blocks / 10);
    }

    private static void changes(boolean on) {
        SurfaceWrites.setEnabled(on);
        BiomeCells.setEnabled(on);
        Heights.setEnabled(on);
        DensityBranches.setEnabled(on);
        PositionalRandoms.setEnabled(on);
        SurfaceBuffers.setEnabled(on);
    }

    /** The vanilla overworld for a seed. */
    private static final class World {
        private final NoiseBasedChunkGenerator generator;
        private final RandomState randomState;
        private final long seed;

        World(long seed) {
            this.seed = seed;
            Holder<NoiseGeneratorSettings> settings =
                    registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            generator = new NoiseBasedChunkGenerator(
                    MultiNoiseBiomeSource.createFromPreset(registries
                            .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                            .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                    settings);
            randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
        }

        /**
         * The terrain of the 4x4 chunks from {@code chunkX}, {@code chunkZ}, with biomes made for a
         * chunk's width around them too, as the game has them by then.
         */
        Map<ChunkPos, ProtoChunk> terrain(int chunkX, int chunkZ) {
            Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
            StructureManager structures = noStructures();
            for (int x = chunkX - 1; x <= chunkX + 4; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 4; z++) {
                    ProtoChunk chunk = new ProtoChunk(
                            new ChunkPos(x, z),
                            UpgradeData.EMPTY,
                            LevelHeightAccessor.create(-64, 384),
                            containers,
                            null);
                    generator
                            .createBiomes(randomState, Blender.empty(), structures, chunk)
                            .join();
                    chunk.setPersistedStatus(ChunkStatus.BIOMES);
                    chunks.put(chunk.getPos(), chunk);
                }
            }
            BiomeManager biomes = new BiomeManager(
                    (x, y, z) -> chunks.get(new ChunkPos(x >> 2, z >> 2)).getNoiseBiome(x, y, z),
                    BiomeManager.obfuscateSeed(seed));
            Map<ChunkPos, ProtoChunk> built = new HashMap<>();
            for (int x = chunkX; x < chunkX + 4; x++) {
                for (int z = chunkZ; z < chunkZ + 4; z++) {
                    ProtoChunk chunk = chunks.get(new ChunkPos(x, z));
                    generator
                            .buildTerrain(
                                    chunk,
                                    Blender.empty(),
                                    randomState,
                                    structures,
                                    biomes,
                                    null,
                                    possibleBiomes(chunks, x, z))
                            .join();
                    built.put(chunk.getPos(), chunk);
                }
            }
            return built;
        }

        /** The biomes of the chunk and its neighbours, as the game collects them for its surface. */
        private static Set<Holder<Biome>> possibleBiomes(Map<ChunkPos, ProtoChunk> chunks, int chunkX, int chunkZ) {
            Set<Holder<Biome>> biomes = new HashSet<>();
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                    ProtoChunk chunk = chunks.get(new ChunkPos(x, z));
                    for (int qy = -16; qy < 80; qy++) {
                        for (int qx = 0; qx < 4; qx++) {
                            for (int qz = 0; qz < 4; qz++) {
                                biomes.add(chunk.getNoiseBiome(x * 4 + qx, qy, z * 4 + qz));
                            }
                        }
                    }
                }
            }
            return biomes;
        }

        /** A structure manager with no structures anywhere. */
        @SuppressWarnings("NullAway") // Never reads its level: every lookup is answered here.
        private static StructureManager noStructures() {
            return new StructureManager(null, null, null) {
                @Override
                public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> matcher) {
                    return List.of();
                }
            };
        }
    }
}
