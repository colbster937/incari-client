package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.serialization.DynamicOps;
import incari.client.testing.VanillaRegistries;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortList;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A chunk's terrain built by a helper ({@link RemoteTerrain}, ADR 0042) is the chunk the server
 * builds itself: the job written from the server's chunks, the helper's own registries and
 * generator set up from the server's description, its result put into a fresh chunk. Every
 * section's blocks are compared as the game writes them, palette and all, with the heightmaps and
 * the fluids to update.
 */
class RemoteTerrainTest {

    private static final long SEED = 20260928L;
    private static RegistryAccess.Frozen registries;
    private static PalettedContainerFactory containers;
    private static NoiseBasedChunkGenerator generator;
    private static RandomState randomState;
    private static Map<Integer, RemoteTerrain.Built> helper;

    @BeforeAll
    static void setUp() throws Exception {
        registries = VanillaRegistries.load();
        containers = PalettedContainerFactory.create(registries);
        Holder<NoiseGeneratorSettings> settings =
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        generator = new NoiseBasedChunkGenerator(
                MultiNoiseBiomeSource.createFromPreset(registries
                        .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                settings);
        randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), SEED, settings.value());
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        CompoundTag setup = new CompoundTag();
        setup.putInt("biomes", RemoteTerrain.biomeNumbering(registries));
        CompoundTag entry = new CompoundTag();
        entry.putInt("id", 0);
        entry.putLong("seed", SEED);
        entry.putInt("min_y", -64);
        entry.putInt("height", 384);
        entry.put("generator", ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow());
        ListTag generators = new ListTag();
        generators.add(entry);
        setup.put("generators", generators);
        helper = RemoteTerrain.setUp(
                RemoteTerrain.loadRegistries(WorldDataConfiguration.CODEC
                        .encodeStart(NbtOps.INSTANCE, WorldDataConfiguration.DEFAULT)
                        .getOrThrow()),
                setup);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "37, -112", "-300, 40", "1000, 1000"})
    void aChunkBuiltByTheHelperIsTheChunkBuiltHere(int chunkX, int chunkZ) {
        for (int x = chunkX; x < chunkX + 2; x++) {
            for (int z = chunkZ; z < chunkZ + 2; z++) {
                Map<ChunkPos, ProtoChunk> here = biomes(x, z);
                ProtoChunk expected = here.get(new ChunkPos(x, z));
                buildHere(expected, here);

                Map<ChunkPos, ProtoChunk> there = biomes(x, z);
                ProtoChunk actual = there.get(new ChunkPos(x, z));
                byte[] job =
                        TerrainJob.encode(7, 0, actual, (cx, cz) -> there.get(new ChunkPos(cx, cz)), Beardifier.EMPTY);
                byte[] result = RemoteTerrain.build(job, helper);
                assertThat(TerrainJob.resultId(result)).isEqualTo(7);
                TerrainJob.apply(result, actual);

                assertSame(expected, actual);
            }
        }
    }

    @Test
    void structuresNearAChunkCrossAsTheyAre() {
        Beardifier beardifier = new Beardifier(
                List.of(new Beardifier.Rigid(new BoundingBox(1, 60, 2, 20, 75, 18), TerrainAdjustment.BEARD_THIN, 3)),
                List.of(new JigsawJunction(5, 64, 7, -2, StructureTemplatePool.Projection.TERRAIN_MATCHING)),
                new BoundingBox(-23, 36, -22, 44, 99, 42));
        Map<ChunkPos, ProtoChunk> chunks = biomes(0, 0);
        ProtoChunk chunk = chunks.get(new ChunkPos(0, 0));
        byte[] job = TerrainJob.encode(1, 0, chunk, (cx, cz) -> chunks.get(new ChunkPos(cx, cz)), beardifier);

        TerrainJob.Job read = TerrainJob.decode(job, LevelHeightAccessor.create(-64, 384), containers);

        Beardifier back = read.beardifier();
        assertThat(back.pieces()).isEqualTo(beardifier.pieces());
        assertThat(back.affectedBox()).isEqualTo(beardifier.affectedBox());
        assertThat(back.junctions()).hasSize(1);
        JigsawJunction junction = back.junctions().getFirst();
        assertThat(List.of(
                        junction.getSourceX(),
                        junction.getSourceGroundY(),
                        junction.getSourceZ(),
                        junction.getDeltaY()))
                .containsExactly(5, 64, 7, -2);
        assertThat(junction.getDestProjection()).isEqualTo(StructureTemplatePool.Projection.TERRAIN_MATCHING);
        assertThat(read.chunk().getPersistedStatus()).isEqualTo(ChunkStatus.BIOMES);
        Set<Holder<Biome>> possible = new HashSet<>();
        for (ProtoChunk around : chunks.values()) {
            around.collectBiomesInPalette(possible);
        }
        assertThat(read.possibleBiomes()).isEqualTo(possible);
    }

    private static void assertSame(ProtoChunk expected, ProtoChunk actual) {
        LevelChunkSection[] expectedSections = expected.getSections();
        LevelChunkSection[] actualSections = actual.getSections();
        long solid = 0;
        for (int i = 0; i < expectedSections.length; i++) {
            assertThat(written(actualSections[i]))
                    .as("%s section %d", expected.getPos(), i)
                    .isEqualTo(written(expectedSections[i]));
            assertThat(actualSections[i].hasOnlyAir()).isEqualTo(expectedSections[i].hasOnlyAir());
            assertThat(actualSections[i].hasFluid()).isEqualTo(expectedSections[i].hasFluid());
            assertThat(actualSections[i].isRandomlyTicking()).isEqualTo(expectedSections[i].isRandomlyTicking());
            if (!expectedSections[i].hasOnlyAir()) {
                solid++;
            }
        }
        assertThat(solid).as("terrain was generated").isGreaterThan(3);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            assertThat(actual.hasPrimedHeightmap(type)).isEqualTo(expected.hasPrimedHeightmap(type));
            if (expected.hasPrimedHeightmap(type)) {
                assertThat(actual.getOrCreateHeightmapUnprimed(type).getRawData())
                        .isEqualTo(expected.getOrCreateHeightmapUnprimed(type).getRawData());
            }
        }
        ShortList[] expectedLists = expected.getPostProcessing();
        ShortList[] actualLists = actual.getPostProcessing();
        for (int i = 0; i < expectedLists.length; i++) {
            assertThat(actualLists[i] == null ? List.of() : List.copyOf(actualLists[i]))
                    .as("%s fluids to update in section %d", expected.getPos(), i)
                    .isEqualTo(expectedLists[i] == null ? List.of() : List.copyOf(expectedLists[i]));
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(expected.getPos().getMinBlockX(), 0, expected.getPos().getMinBlockZ());
        assertThat(actual.getBlockState(pos)).isEqualTo(expected.getBlockState(pos));
    }

    /** A section's blocks as the game writes them: palette, then the packed ids. */
    private static byte[] written(LevelChunkSection section) {
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        section.getStates().write(out);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    /** The chunk at x, z and the eight around it, at the biomes step, as the server has them for its terrain. */
    private static Map<ChunkPos, ProtoChunk> biomes(int chunkX, int chunkZ) {
        Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
        StructureManager structures = noStructures();
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                ProtoChunk chunk = new ProtoChunk(
                        new ChunkPos(x, z), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), containers, null);
                generator
                        .createBiomes(randomState, Blender.empty(), structures, chunk)
                        .join();
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                chunks.put(chunk.getPos(), chunk);
            }
        }
        return chunks;
    }

    /** Builds the chunk's terrain here, as the server does with its region. */
    private static void buildHere(ProtoChunk chunk, Map<ChunkPos, ProtoChunk> chunks) {
        BiomeResolver beyond = generator.getBiomeSource().createUncachedResolver(randomState);
        BiomeManager biomes = new BiomeManager(
                (x, y, z) -> {
                    ChunkAccess around = chunks.get(new ChunkPos(x >> 2, z >> 2));
                    return around != null ? around.getNoiseBiome(x, y, z) : beyond.getNoiseBiome(x, y, z);
                },
                BiomeManager.obfuscateSeed(SEED));
        Set<Holder<Biome>> possible = new HashSet<>();
        for (ProtoChunk around : chunks.values()) {
            around.collectBiomesInPalette(possible);
        }
        generator
                .buildTerrain(chunk, Blender.empty(), randomState, noStructures(), biomes, null, possible)
                .join();
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
