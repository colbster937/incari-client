package incari.client.memory;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.TestPlatform;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every block state's cache, built with what states share and sturdy faces worked out once
 * per support shape, answers as the game's own computation does.
 */
class BlockStateSharingTest {

    @BeforeAll
    static void bootstrap() {
        TestPlatform.install();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sturdyFacesAreTheGamesAnswers() {
        int states = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                states++;
                for (Direction direction : Direction.values()) {
                    for (SupportType type : SupportType.values()) {
                        assertThat(state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction, type))
                                .as("%s, %s face, %s", state, direction, type)
                                .isEqualTo(
                                        type.isSupporting(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction));
                    }
                }
            }
        }
        assertThat(states).isGreaterThan(20000);
    }

    @Test
    void fullBlocksAreTheGamesAnswers() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                assertThat(state.isSolidRender())
                        .as("%s", state)
                        .isEqualTo(Block.isShapeFullBlock(state.getOcclusionShape()));
                assertThat(state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                        .as("%s", state)
                        .isEqualTo(Block.isShapeFullBlock(
                                state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
            }
        }
    }

    @Test
    void occlusionFacesAreTheShapesFaces() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.isSolidRender() || state.getOcclusionShape().isEmpty()) {
                    continue; // The game's shared constant arrays, untouched.
                }
                for (Direction direction : Direction.values()) {
                    assertThat(state.getFaceOcclusionShape(direction))
                            .as("%s, %s face", state, direction)
                            .isSameAs(state.getOcclusionShape().getFaceShape(direction));
                }
            }
        }
    }
}
