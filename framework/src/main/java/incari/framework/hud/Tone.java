package incari.framework.hud;

/**
 * What a color is for, not which color it is. HUD elements draw in tones and the client's theme
 * decides the colors, so restyling the client restyles every module's HUD with it. A color the
 * player picked (a {@code ColorSetting}) is drawn as given instead.
 */
public enum Tone {
    /** Ordinary text. */
    TEXT,
    /** Secondary text: labels, units, what's off. */
    MUTED,
    /** The client's accent: what's on, what's chosen. */
    ACCENT,
    /** Good news: full, ready, safe. */
    POSITIVE,
    /** Worth a look: running low. */
    WARNING,
    /** Bad news: empty, broken, in danger. */
    NEGATIVE,
    /** A panel behind an element's text. */
    BACKGROUND,
    /** A panel's edge, catching the light: a frame around a map. */
    EDGE,
    /** Text on an {@link #ACCENT} fill: a pressed key's name. */
    ON_ACCENT
}
