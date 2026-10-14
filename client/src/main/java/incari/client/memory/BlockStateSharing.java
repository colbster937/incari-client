package incari.client.memory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Arrays every block state keeps a copy of, shared between the states whose copies are equal
 * (block-state de-duplication, ADR 0025). The game's 26.x states already share their
 * neighbour tables (what FerriteCore once added); what each still made for itself is the array of
 * its occlusion shape's six faces, though states with the same shape have the same six faces, and
 * the table of which faces are sturdy, of which there are a few hundred kinds among 35,000 states.
 * Sharing them is safe because neither is ever written after the state's cache is built.
 *
 * <p>For the same reason it works out which faces are sturdy once per support shape rather than
 * once per state: each of a state's 18 answers depends only on its support shape and the
 * face, most states share their shape object with others, and the 630,000 answers were 60% of
 * building the block states' caches at start-up. Whether a shape fills the block is asked once per
 * shape too.
 *
 * <p>Built once, at bootstrap, on one thread; the interning maps are dropped with it.
 */
public final class BlockStateSharing {

    // Keyed by the faces themselves: a shape's faces are the same objects every time it's asked
    // (VoxelShape keeps them), and shapes compare by identity.
    private static @Nullable Map<List<VoxelShape>, VoxelShape[]> faces = new HashMap<>();
    private static @Nullable Map<BooleanKey, boolean[]> flags = new HashMap<>();
    // Per support shape: bit i set if answer i is known, bit 32 + i its value (i = type, face).
    private static @Nullable IdentityHashMap<VoxelShape, long[]> support = new IdentityHashMap<>();
    private static @Nullable IdentityHashMap<VoxelShape, Boolean> fullBlocks = new IdentityHashMap<>();

    private BlockStateSharing() {}

    /** Hook: the array of faces equal to {@code shapes} that other states already use, or {@code shapes}. */
    public static VoxelShape[] faces(VoxelShape[] shapes) {
        Map<List<VoxelShape>, VoxelShape[]> known = faces;
        return known == null ? shapes : known.computeIfAbsent(Arrays.asList(shapes.clone()), key -> shapes);
    }

    /** Hook: the table equal to {@code table} that other states already use, or {@code table}. */
    public static boolean[] flags(boolean[] table) {
        Map<BooleanKey, boolean[]> known = flags;
        return known == null ? table : known.computeIfAbsent(new BooleanKey(table.clone()), key -> table);
    }

    /**
     * Hook: whether {@code state}'s {@code direction} face is sturdy for {@code type}, as the
     * game's cache asks it (of an empty level, at the origin): the answer for the state's support
     * shape, worked out the first time any state with that shape asks.
     */
    // The ordinals only number the answers kept in memory for the length of the bootstrap.
    @SuppressWarnings("EnumOrdinal")
    public static boolean isSupporting(SupportType type, BlockState state, Direction direction) {
        IdentityHashMap<VoxelShape, long[]> known = support;
        if (known == null) {
            return type.isSupporting(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction);
        }
        long[] answers = known.computeIfAbsent(
                state.getBlockSupportShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), shape -> new long[1]);
        int bit = type.ordinal() * Direction.values().length + direction.ordinal();
        if ((answers[0] & (1L << bit)) == 0) {
            boolean value = type.isSupporting(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction);
            answers[0] |= (1L << bit) | (value ? 1L << (32 + bit) : 0);
        }
        return (answers[0] & (1L << (32 + bit))) != 0;
    }

    /**
     * Hook: whether {@code shape} fills the block, as the game's {@code Block.isShapeFullBlock}
     * answers it, asked once per shape while the states' caches are built (the game's own cache of
     * the answer is a Guava cache, whose every lookup costs microseconds in the browser).
     */
    public static boolean isFullBlock(VoxelShape shape) {
        IdentityHashMap<VoxelShape, Boolean> known = fullBlocks;
        return known == null ? Block.isShapeFullBlock(shape) : known.computeIfAbsent(shape, Block::isShapeFullBlock);
    }

    /** Hook: every state's cache is built; what's shared stays shared, and the maps go. */
    public static void done() {
        faces = null;
        flags = null;
        support = null;
        fullBlocks = null;
    }

    /** An array of booleans compared by its contents. */
    private static final class BooleanKey {
        private final boolean[] values;

        BooleanKey(boolean[] values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BooleanKey key && Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }
}
