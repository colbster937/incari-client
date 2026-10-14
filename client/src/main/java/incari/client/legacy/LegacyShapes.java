package incari.client.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Blocks whose shape changed after 1.8, as a 1.8 server simulates them (ADR 0020). A 1.8
 * server moves the player against these shapes; a client that collides with the newer ones
 * rubber-bands (a ladder a sixteenth thicker, a lily pad six times taller) or is flagged by the
 * server's movement checks.
 *
 * <ul>
 *   <li>ladders: 2/16 thick, against the wall they hang on (3/16 since 1.9);
 *   <li>lily pads: a full square, 1/64 high (a smaller square 1.5/16 high since 1.9);
 *   <li>glass panes and iron bars: arms along the directions they connect in, an arm through the
 *       whole block where they connect both ways or not at all, no post; the outline is the box
 *       around them (a post with arms since 1.9);
 *   <li>anvils: a full-height box, 12/16 wide across their length (a stepped shape since 1.13);
 *   <li>farmland: a full block to collide with, 15/16 to look at (15/16 both since 1.10).
 * </ul>
 *
 * Called from the two shape getters of every block state, in hot paths: one field read when off.
 */
public final class LegacyShapes {

    private static final VoxelShape LADDER_NORTH = Shapes.box(0, 0, 14 / 16.0, 1, 1, 1);
    private static final VoxelShape LADDER_SOUTH = Shapes.box(0, 0, 0, 1, 1, 2 / 16.0);
    private static final VoxelShape LADDER_WEST = Shapes.box(14 / 16.0, 0, 0, 1, 1, 1);
    private static final VoxelShape LADDER_EAST = Shapes.box(0, 0, 0, 2 / 16.0, 1, 1);
    private static final VoxelShape LILY_PAD = Shapes.box(0, 0, 0, 1, 1 / 64.0, 1);
    private static final VoxelShape ANVIL_X = Shapes.box(0, 0, 2 / 16.0, 1, 1, 14 / 16.0);
    private static final VoxelShape ANVIL_Z = Shapes.box(2 / 16.0, 0, 0, 14 / 16.0, 1, 1);
    /** Panes by their connections (north 1, east 2, south 4, west 8): what collides, and the outline. */
    private static final VoxelShape[] PANE_COLLISION = new VoxelShape[16];

    private static final VoxelShape[] PANE_OUTLINE = new VoxelShape[16];

    static {
        double thin0 = 7 / 16.0;
        double thin1 = 9 / 16.0;
        for (int mask = 0; mask < 16; mask++) {
            boolean north = (mask & 1) != 0;
            boolean east = (mask & 2) != 0;
            boolean south = (mask & 4) != 0;
            boolean west = (mask & 8) != 0;
            boolean any = mask != 0;
            // East-west arm: through the block if it connects both ways or not at all, else half.
            double x0 = 0;
            double x1 = 1;
            boolean xArm = true;
            if ((!west || !east) && any) {
                if (west) {
                    x1 = 0.5;
                } else if (east) {
                    x0 = 0.5;
                } else {
                    xArm = false;
                }
            }
            double z0 = 0;
            double z1 = 1;
            boolean zArm = true;
            if ((!north || !south) && any) {
                if (north) {
                    z1 = 0.5;
                } else if (south) {
                    z0 = 0.5;
                } else {
                    zArm = false;
                }
            }
            VoxelShape collision = Shapes.empty();
            if (xArm) {
                collision = Shapes.or(collision, Shapes.box(x0, 0, thin0, x1, 1, thin1));
            }
            if (zArm) {
                collision = Shapes.or(collision, Shapes.box(thin0, 0, z0, thin1, 1, z1));
            }
            PANE_COLLISION[mask] = collision;
            PANE_OUTLINE[mask] =
                    Shapes.box(xArm ? x0 : thin0, 0, zArm ? z0 : thin0, xArm ? x1 : thin1, 1, zArm ? z1 : thin1);
        }
    }

    private LegacyShapes() {}

    /** {@code BlockStateBase.getShape}: the outline 1.8 gives {@code state}, or null for the current one. */
    public static @Nullable VoxelShape outline(BlockState state, BlockGetter level, BlockPos pos) {
        if (!LegacyRules.blockShapes18) {
            return null;
        }
        return shared(state);
    }

    /** {@code BlockStateBase.getCollisionShape}: what 1.8 collides with, or null for the current shape. */
    public static @Nullable VoxelShape collision(BlockState state, BlockGetter level, BlockPos pos) {
        if (!LegacyRules.blockShapes18) {
            return null;
        }
        if (state.getBlock() instanceof FarmlandBlock) {
            return Shapes.block();
        }
        if (state.getBlock() instanceof IronBarsBlock) {
            return PANE_COLLISION[paneMask(state)];
        }
        return shared(state);
    }

    /** Shapes that are the same to look at and to collide with. */
    private static @Nullable VoxelShape shared(BlockState state) {
        var block = state.getBlock();
        if (block instanceof LadderBlock) {
            return switch (state.getValue(LadderBlock.FACING)) {
                case SOUTH -> LADDER_SOUTH;
                case WEST -> LADDER_WEST;
                case EAST -> LADDER_EAST;
                default -> LADDER_NORTH;
            };
        }
        if (block instanceof LilyPadBlock) {
            return LILY_PAD;
        }
        if (block instanceof AnvilBlock) {
            return state.getValue(AnvilBlock.FACING).getAxis() == Direction.Axis.X ? ANVIL_X : ANVIL_Z;
        }
        if (block instanceof IronBarsBlock) {
            return PANE_OUTLINE[paneMask(state)];
        }
        return null;
    }

    private static int paneMask(BlockState state) {
        return (state.getValue(CrossCollisionBlock.NORTH) ? 1 : 0)
                | (state.getValue(CrossCollisionBlock.EAST) ? 2 : 0)
                | (state.getValue(CrossCollisionBlock.SOUTH) ? 4 : 0)
                | (state.getValue(CrossCollisionBlock.WEST) ? 8 : 0);
    }
}
