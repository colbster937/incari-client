package incari.framework.game;

/** Whether an effect helps the player or hurts them. */
public enum EffectCategory {
    /** Speed, strength, regeneration. */
    BENEFICIAL,
    /** Poison, slowness, weakness. */
    HARMFUL,
    /** Neither: glowing, say. */
    NEUTRAL
}
