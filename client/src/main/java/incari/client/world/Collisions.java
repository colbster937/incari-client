package incari.client.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Block collisions without asking air for a shape (Lithium's collision sweeps, in part). The game
 * gathers an entity's block collisions by visiting every block in its box and one around it, and
 * for each asks the collision context for the block's shape and moves it into place. Most of those
 * blocks are air, and the three air blocks (air, cave air, void air) have no shape whatever the
 * context: their {@code getShape} is empty and they have no collision. So air is passed over before
 * that, with the same result. In the combat scenario on the floor stand-in, dropped items' movement
 * spent a third of its time in these sweeps.
 */
public final class Collisions {

    private static boolean enabled;

    private Collisions() {}

    /** Off unless the page turns it on ({@code ?collisions=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code BlockCollisions.computeNext}): whether the block at the cursor can't collide. */
    public static boolean passOver(BlockState state) {
        return enabled && state.isAir();
    }
}
