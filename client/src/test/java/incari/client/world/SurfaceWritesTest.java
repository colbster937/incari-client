package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.VanillaRegistries;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Writing a chunk's surface through {@link SurfaceWrites} leaves it as the game's own writes do:
 * the same blocks and the same heightmaps, after the same writes to two copies of a chunk, and the
 * same blocks read back along the way.
 */
class SurfaceWritesTest {

    /** What the writes put down; filled once the game is bootstrapped. */
    private static BlockState[] states;

    private static BlockState[] states() {
        return new BlockState[] {
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.SAND.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            Blocks.CAVE_AIR.defaultBlockState(),
            Blocks.SNOW.defaultBlockState(),
            Blocks.PACKED_ICE.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(),
            Blocks.BEDROCK.defaultBlockState(),
        };
    }

    private static PalettedContainerFactory containers;

    @BeforeAll
    static void bootstrap() {
        containers = PalettedContainerFactory.create(VanillaRegistries.load());
        states = states();
    }

    @AfterEach
    void off() {
        SurfaceWrites.setEnabled(false);
    }

    @Test
    void writesLeaveTheChunkAsTheGamesWritesDo() {
        Random random = new Random(26_3L);
        ChunkPos at = new ChunkPos(3, -2);
        ProtoChunk game = terrain(at, new Random(7));
        ProtoChunk ours = terrain(at, new Random(7));

        SurfaceWrites.setEnabled(true);
        SurfaceWrites writes = SurfaceWrites.of(ours);
        assertThat(writes).isNotNull();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 40_000; i++) {
            int x = at.getMinBlockX() + random.nextInt(16);
            int z = at.getMinBlockZ() + random.nextInt(16);
            // Mostly around the surface and below it, as surface rules write; now and then out of range.
            int y =
                    switch (random.nextInt(10)) {
                        case 0 -> -70 + random.nextInt(8);
                        case 1 -> 315 + random.nextInt(10);
                        default -> -64 + random.nextInt(200);
                    };
            pos.set(x, y, z);
            assertThat(writes.get(pos)).as("before %s", pos).isEqualTo(game.getBlockState(pos));
            BlockState state = states[random.nextInt(states.length)];
            game.setBlockState(pos, state);
            writes.set(pos, state);
        }

        for (int y = -70; y < 330; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    pos.set(at.getMinBlockX() + x, y, at.getMinBlockZ() + z);
                    assertThat(ours.getBlockState(pos)).as("%s", pos).isEqualTo(game.getBlockState(pos));
                }
            }
        }
        for (Heightmap.Types type : ChunkStatus.BIOMES.heightmapsAfter()) {
            assertThat(ours.getOrCreateHeightmapUnprimed(type).getRawData())
                    .as("%s", type)
                    .isEqualTo(game.getOrCreateHeightmapUnprimed(type).getRawData());
        }
    }

    @Test
    void chunksPastLightOrWithoutHeightmapsAreLeftToTheGame() {
        SurfaceWrites.setEnabled(true);
        ProtoChunk lit = terrain(new ChunkPos(0, 0), new Random(1));
        lit.setPersistedStatus(ChunkStatus.INITIALIZE_LIGHT);
        assertThat(SurfaceWrites.of(lit)).isNull();

        ProtoChunk bare = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height(), containers, null);
        bare.setPersistedStatus(ChunkStatus.BIOMES);
        assertThat(SurfaceWrites.of(bare)).isNull();

        SurfaceWrites.setEnabled(false);
        assertThat(SurfaceWrites.of(terrain(new ChunkPos(0, 0), new Random(1)))).isNull();
    }

    /** A chunk of hills and seas, as noise leaves one for its surface: stone, deepslate below 0, water to 62. */
    private static ProtoChunk terrain(ChunkPos at, Random random) {
        ProtoChunk chunk = new ProtoChunk(at, UpgradeData.EMPTY, height(), containers, null);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = -20 + random.nextInt(120);
                for (int y = -64; y <= Math.max(top, 62); y++) {
                    BlockState state = y <= top
                            ? (y < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState()
                            : Blocks.WATER.defaultBlockState();
                    chunk.setBlockState(pos.set(at.getMinBlockX() + x, y, at.getMinBlockZ() + z), state);
                }
            }
        }
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return chunk;
    }

    private static LevelHeightAccessor height() {
        return LevelHeightAccessor.create(-64, 384);
    }
}
