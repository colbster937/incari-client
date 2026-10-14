package incari.framework.game;

/**
 * A status effect on the player, as modules see it. Drawn with {@code HudCanvas.effect}. Reused by
 * the client: read it during the call that handed it out.
 */
public interface EffectView {

    /** The effect's name in the player's language ("Speed"); the same string each call. */
    String name();

    /** 0 for level I, 1 for level II, and so on. */
    int amplifier();

    /** Ticks left (20 a second); meaningless if {@link #isInfinite()}. */
    int duration();

    /** Whether it never runs out. */
    boolean isInfinite();

    /** Whether it helps or hurts. */
    EffectCategory category();
}
