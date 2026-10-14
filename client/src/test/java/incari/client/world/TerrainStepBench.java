package incari.client.world;

import incari.client.testing.VanillaRegistries;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Times the overworld's terrain step (biomes, then noise, surface and carvers) on the JVM, with the
 * browser's world generation changes on: 48 chunks a round, 16 rounds, the last 8 reported. For
 * comparing a world generation change before a browser build; JVM shares don't transfer to the page
 * exactly, but this step is plain computation in both. Runs only with {@code INCARI_BENCH} set:
 * {@code INCARI_BENCH=1 ./gradlew :client:test --tests '*TerrainStepBench*' -i}.
 */
class TerrainStepBench {

    private static final int ROUNDS = 16;

    @Test
    void terrainStep() {
        Assumptions.assumeTrue(System.getenv("INCARI_BENCH") != null, "INCARI_BENCH not set");
        RegistryAccess.Frozen registries = VanillaRegistries.load();
        PalettedContainerFactory containers = PalettedContainerFactory.create(registries);
        SurfaceWrites.setEnabled(true);
        BiomeCells.setEnabled(true);
        Heights.setEnabled(true);
        DensityBranches.setEnabled(on("densitychoices"));
        PositionalRandoms.setEnabled(on("randoms"));
        SurfaceBuffers.setEnabled(on("surfacebuffers"));
        Holder<NoiseGeneratorSettings> settings =
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(
                MultiNoiseBiomeSource.createFromPreset(registries
                        .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                settings);
        long seed = 20260928L;
        RandomState randomState =
                RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
        StructureManager structures = noStructures();
        long total = 0;
        for (int round = 0; round < ROUNDS; round++) {
            // A new area each round, as a flight over fresh terrain.
            int chunkX = round * 8;
            Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
            for (int x = chunkX - 1; x <= chunkX + 8; x++) {
                for (int z = -1; z <= 6; z++) {
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
            long start = System.nanoTime();
            for (int x = chunkX; x < chunkX + 8; x++) {
                for (int z = 0; z < 6; z++) {
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
                }
            }
            long elapsed = System.nanoTime() - start;
            if (round >= ROUNDS / 2) {
                total += elapsed;
            }
            System.out.printf("round %2d: %.2f ms a chunk%n", round, elapsed / 48.0 / 1e6);
        }
        System.out.printf(
                "terrain step: %.3f ms a chunk over the last %d rounds%n",
                total / 48.0 / (ROUNDS / 2) / 1e6, ROUNDS / 2);
    }

    /** A change is on unless {@code INCARI_BENCH_OFF} names it (comma-separated), to compare. */
    private static boolean on(String name) {
        String off = System.getenv("INCARI_BENCH_OFF");
        return off == null || !List.of(off.split(",")).contains(name);
    }

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
