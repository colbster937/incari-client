package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link GoalFlags} answers as the game's flag loops do, for every set of flags a goal can hold. */
@SuppressWarnings("EnumOrdinal") // As GoalFlags numbers flags.
class GoalFlagsTest {

    private static final Goal.Flag[] FLAGS = Goal.Flag.values();

    @AfterEach
    void off() {
        GoalFlags.setEnabled(false);
    }

    @Test
    void masksAnswerAsTheFlagSetsDo() {
        int sets = 1 << FLAGS.length;
        WrappedGoal noGoal = new WrappedGoal(Integer.MAX_VALUE, goal(EnumSet.noneOf(Goal.Flag.class), true));
        long compared = 0;
        for (int held = 0; held < sets; held++) {
            for (int priority : new int[] {0, 3, Integer.MAX_VALUE}) {
                WrappedGoal goal = new WrappedGoal(priority, goal(flags(held), true));
                for (int disabled = 0; disabled < sets; disabled++) {
                    EnumSet<Goal.Flag> disabledSet = flags(disabled);
                    for (int lockedBy = 0; lockedBy < sets; lockedBy++) {
                        Map<Goal.Flag, WrappedGoal> locked = new EnumMap<>(Goal.Flag.class);
                        for (Goal.Flag flag : flags(lockedBy)) {
                            // Holders that may and may not be replaced, by priority and interruptibility.
                            locked.put(
                                    flag,
                                    new WrappedGoal(
                                            flag.ordinal() * 2, goal(EnumSet.of(flag), flag.ordinal() % 2 == 0)));
                        }
                        GoalFlags.setEnabled(false);
                        boolean gameContains = GoalFlags.containsAny(goal, disabledSet, disabled);
                        boolean gameReplaces = GoalFlags.canReplaceAll(goal, locked, noGoal);
                        GoalFlags.setEnabled(true);
                        assertThat(GoalFlags.containsAny(goal, disabledSet, GoalFlags.mask(disabledSet)))
                                .isEqualTo(gameContains);
                        assertThat(GoalFlags.canReplaceAll(goal, locked, noGoal))
                                .isEqualTo(gameReplaces);
                        compared++;
                    }
                }
            }
        }
        assertThat(compared).isEqualTo(3L * sets * sets * sets);
    }

    @Test
    void aGoalsMaskFollowsItsFlags() {
        Goal goal = goal(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK), true);
        assertThat(goal.incariFlagMask()).isEqualTo(GoalFlags.mask(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK)));
        goal.setFlags(EnumSet.of(Goal.Flag.JUMP));
        assertThat(goal.incariFlagMask()).isEqualTo(1 << Goal.Flag.JUMP.ordinal());
    }

    private static EnumSet<Goal.Flag> flags(int mask) {
        EnumSet<Goal.Flag> set = EnumSet.noneOf(Goal.Flag.class);
        for (Goal.Flag flag : FLAGS) {
            if ((mask & 1 << flag.ordinal()) != 0) {
                set.add(flag);
            }
        }
        return set;
    }

    private static Goal goal(EnumSet<Goal.Flag> flags, boolean interruptable) {
        Goal goal = new Goal() {
            @Override
            public boolean canUse() {
                return false;
            }

            @Override
            public boolean isInterruptable() {
                return interruptable;
            }
        };
        goal.setFlags(flags);
        return goal;
    }
}
