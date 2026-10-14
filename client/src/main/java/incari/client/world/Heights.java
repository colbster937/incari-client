package incari.client.world;

import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * A level's or chunk's build height, read once (Lithium's cached world height). The game asks for
 * the lowest block and the height all the time (every block a chunk reads or writes checks it is
 * inside them), and each answer goes from the chunk through its level's interface to the
 * dimension type's holder: in the singleplayer benchmark's fly-through, those calls were 3 to 4% of
 * generating terrain. A dimension type never changes once a level has it, and a chunk's level
 * never changes, so the first answer is every answer.
 *
 * <p>Made per level and per chunk ({@code Level}'s and {@code ChunkAccess}'s constructors). The
 * first read may come from any thread; two threads reading first both store the same values.
 */
public final class Heights {

    private static boolean enabled;

    private final @Nullable Holder<DimensionType> type;
    private final @Nullable LevelHeightAccessor accessor;
    private int minY;
    private int height;
    private volatile boolean known;

    private Heights(@Nullable Holder<DimensionType> type, @Nullable LevelHeightAccessor accessor) {
        this.type = type;
        this.accessor = accessor;
    }

    /** Off unless the page turns it on ({@code ?heights=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code Level}'s constructor): a level's heights, those of its dimension type. */
    public static Heights of(Holder<DimensionType> type) {
        return new Heights(type, null);
    }

    /** Hook ({@code ChunkAccess}'s constructor): a chunk's heights, those of what it was made in. */
    public static Heights of(LevelHeightAccessor accessor) {
        return new Heights(null, accessor);
    }

    /** The lowest block's height. Hot path. */
    public int minY() {
        if (!enabled) {
            return readMinY();
        }
        if (!known) {
            learn();
        }
        return minY;
    }

    /** How many blocks high the level is. Hot path. */
    public int height() {
        if (!enabled) {
            return readHeight();
        }
        if (!known) {
            learn();
        }
        return height;
    }

    private void learn() {
        minY = readMinY();
        height = readHeight();
        known = true;
    }

    // Exactly one of type and accessor is set.
    @SuppressWarnings("NullAway")
    private int readMinY() {
        return type != null ? type.value().minY() : accessor.getMinY();
    }

    @SuppressWarnings("NullAway")
    private int readHeight() {
        return type != null ? type.value().height() : accessor.getHeight();
    }
}
