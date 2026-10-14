package incari.framework;

import incari.framework.event.TickListener;
import incari.framework.game.Game;
import incari.framework.game.GameOption;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.FloatSetting;
import java.util.Locale;
import java.util.function.DoubleSupplier;

/**
 * One game option a module holds while it's on ({@link Module#holdOption}): set to the module's
 * value when it turns on, given back when it turns off.
 *
 * <p>The player's own value is kept in memory while the hold lasts, and in two hidden settings so
 * it comes back even after a restart with the module on (by then the game's options have saved the
 * module's value). The hidden settings are trusted only at start-up: a profile switch, a preset or
 * Reset All can load copies that say nothing of the option now. During the hold they're set from
 * memory each tick, so the saved profile is right.
 *
 * <p>The option's slider stays the player's: moving it while the module is on turns the module
 * off and keeps what they chose. Checked once a tick, as a comparison of two numbers.
 */
final class OptionHold implements TickListener {

    /** Sliders store doubles; a module's values come from float settings. */
    private static final double SAME = 1e-4;

    private final Module module;
    private final GameOption option;
    private final DoubleSupplier value;
    /** What the player had before the module took it, for after a restart. */
    private final FloatSetting before;
    /** Whether the game's option has the module's value, so {@link #before} is the player's. */
    private final BooleanSetting held;
    /** What the module set it to; NaN while it doesn't hold it. */
    private double applied = Double.NaN;
    /** What the player had before the module took it; NaN while it doesn't hold it. */
    private double original = Double.NaN;

    OptionHold(Module module, GameOption option, DoubleSupplier value) {
        this.module = module;
        this.option = option;
        this.value = value;
        String suffix = option.name().toLowerCase(Locale.ROOT).replace('_', '-');
        this.before = module.settings()
                .decimal("before-" + suffix, option.label() + " before", 0, (float) option.max(), 0.01F, (float)
                        option.defaultValue())
                .hidden();
        this.held = module.settings()
                .bool("held-" + suffix, option.label() + " held", false)
                .hidden();
    }

    /** The option held. */
    GameOption option() {
        return option;
    }

    /** The module turned on: the option takes the module's value. */
    void take(Game game) {
        double now = game.option(option);
        double want = value.getAsDouble();
        // The module's value left from before a restart: the player's is the one kept from then.
        boolean resumed = module.system().startingUp() && held.get() && same(now, want);
        original = resumed ? before.get() : now;
        if (!same(now, want)) {
            game.setOption(option, want);
        }
        applied = want;
        remember();
    }

    /** The module turned off: the player's value back, unless they've set one since. */
    void release(Game game) {
        double was = applied;
        double players = original;
        applied = Double.NaN;
        original = Double.NaN;
        held.set(false);
        if (!Double.isNaN(was) && same(game.option(option), was)) {
            game.setOption(option, players);
        }
    }

    @Override
    public void onTick() {
        if (Double.isNaN(applied)) {
            return;
        }
        Game game = module.system().game();
        double now = game.option(option);
        if (!same(now, applied)) {
            // The player moved the slider: their value stands, and the module lets go.
            applied = Double.NaN;
            original = Double.NaN;
            held.set(false);
            module.setEnabled(false);
            return;
        }
        double want = value.getAsDouble();
        if (!same(want, applied)) {
            // The module's own setting changed.
            game.setOption(option, want);
            applied = want;
        }
        remember();
    }

    /** The hidden settings as the hold is: no change, so no save, when they're so already. */
    private void remember() {
        before.set((float) original);
        held.set(true);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < SAME;
    }
}
