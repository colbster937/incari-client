package incari.client.world;

import java.util.EnumSet;
import java.util.Map;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/**
 * A mob's goal selection with each goal's control flags as a bit mask (Lithium's goal flags, in
 * spirit). Every tick, for every goal a mob has, the game asks whether the goal holds a flag that's
 * disabled and whether every flag it holds may be taken from the goal holding it now, each by
 * walking the goal's flag set (an iterator each time). In the singleplayer benchmark's spawn
 * scenario on the floor stand-in, the goal selector was the largest part of a mob's own thinking
 * outside its goals. Here the disabled check is one AND of two masks, and the replace check visits
 * only the flags the goal holds, asking the same goal the game would.
 *
 * <p>A goal's mask is kept by {@code Goal.setFlags}, the only way its flags change; a selector's
 * disabled mask by its {@code disableControlFlag} and {@code enableControlFlag}.
 */
// A flag's bit is its ordinal: the masks never leave the program.
@SuppressWarnings("EnumOrdinal")
public final class GoalFlags {

    private static final Goal.Flag[] FLAGS = Goal.Flag.values();
    private static boolean enabled;

    private GoalFlags() {}

    /** Off unless the page turns it on ({@code ?goalflags=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** The mask of a set of flags: bit {@code ordinal} for each. */
    public static int mask(EnumSet<Goal.Flag> flags) {
        int mask = 0;
        for (Goal.Flag flag : flags) {
            mask |= 1 << flag.ordinal();
        }
        return mask;
    }

    /**
     * Hook ({@code GoalSelector.tick}): whether the goal holds a disabled flag, as {@code
     * goalContainsAnyFlags} answers. Hot path.
     */
    public static boolean containsAny(WrappedGoal goal, EnumSet<Goal.Flag> disabled, int disabledMask) {
        if (!enabled) {
            for (Goal.Flag flag : goal.getFlags()) {
                if (disabled.contains(flag)) {
                    return true;
                }
            }
            return false;
        }
        return (goal.getGoal().incariFlagMask() & disabledMask) != 0;
    }

    /**
     * Hook ({@code GoalSelector.tick}): whether every flag the goal holds may be taken from the
     * goal holding it ({@code noGoal} where none does), as {@code goalCanBeReplacedForAllFlags}
     * answers. Hot path.
     */
    public static boolean canReplaceAll(WrappedGoal goal, Map<Goal.Flag, WrappedGoal> locked, WrappedGoal noGoal) {
        if (!enabled) {
            for (Goal.Flag flag : goal.getFlags()) {
                if (!locked.getOrDefault(flag, noGoal).canBeReplacedBy(goal)) {
                    return false;
                }
            }
            return true;
        }
        int mask = goal.getGoal().incariFlagMask();
        while (mask != 0) {
            int bit = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            if (!locked.getOrDefault(FLAGS[bit], noGoal).canBeReplacedBy(goal)) {
                return false;
            }
        }
        return true;
    }
}
