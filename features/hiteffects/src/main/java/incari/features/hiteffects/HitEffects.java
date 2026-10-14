package incari.features.hiteffects;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.game.Effect;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.IntSetting;

/**
 * Hit & Kill Effects: particles on what the player hits, and a bigger burst when it dies. Drawn on
 * the player's screen only; the server and other players see the game's own.
 */
public final class HitEffects extends Module {

    final BooleanSetting onHit =
            settings().bool("on-hit", "On hit", true).describedAs("Particles on whatever you hit.");
    final ChoiceSetting<Effect> hit = settings()
            .choice("hit", "Hit effect", Effect.values(), Effect.MAGIC, Effect::label)
            .describedAs("What shows when you hit.")
            .visibleWhen(onHit::get);
    final IntSetting hitCount = settings()
            .integer("hit-count", "Hit particles", 1, 20, 6)
            .describedAs("How many particles a hit makes.")
            .visibleWhen(onHit::get);
    final BooleanSetting onKill =
            settings().bool("on-kill", "On kill", true).describedAs("A burst when something you hit dies.");
    final ChoiceSetting<Effect> kill = settings()
            .choice("kill", "Kill effect", Effect.values(), Effect.FIREWORK, Effect::label)
            .describedAs("What shows when something you hit dies.")
            .visibleWhen(onKill::get);
    final IntSetting killCount = settings()
            .integer("kill-count", "Kill particles", 5, 60, 30)
            .describedAs("How many particles a kill makes.")
            .visibleWhen(onKill::get);
    final BooleanSetting killSound = settings()
            .bool("kill-sound", "Kill sound", true)
            .describedAs("Plays the effect's sound on a kill.")
            .visibleWhen(onKill::get);

    /** Hit & Kill Effects, off until the player turns it on. */
    public HitEffects() {
        super(
                "hiteffects",
                "Hit & Kill Effects",
                "Shows particles on what you hit and a burst when it dies.",
                Category.COMBAT);
        on(Events.ATTACK, reach -> {
            if (onHit.get()) {
                system().game().effectOnTarget(hit.get(), hitCount.get(), false);
            }
        });
        on(Events.KILL, () -> {
            if (onKill.get()) {
                system().game().effectOnTarget(kill.get(), killCount.get(), killSound.get());
            }
        });
    }
}
