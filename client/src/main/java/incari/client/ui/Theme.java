package incari.client.ui;

import incari.framework.hud.Tone;

/**
 * The client's look, as values: colors by what they're for, and the sizes and spacing Incari's
 * screens are laid out with. Every Incari screen and every module's HUD takes its colors and metrics
 * from here, never from literals, so the restyle (ADR 0048) is a change to this class and
 * to {@link Ui}'s widgets, not to each screen.
 *
 * <p>Frost: white text and translucent white glass over a cold, dimmed backdrop, an ice-blue
 * accent for what's on, chosen or focused. The glass is frosted (the backdrop blurred) only in the
 * Normal {@link GuiPreset}; Potato draws the same shapes in solid, darker fills that read without it.
 *
 * <p>The colors and corner radii are the player's to change (Theme Editor, ADR 0054): {@link
 * #apply} sets them all from a few chosen colors, and Frost's own come back with {@link
 * #restore}. Read them where they're drawn, never copy one into a constant of your own.
 */
@SuppressWarnings("NonFinalStaticField") // The player's theme (Theme Editor) changes them, game thread only.
public final class Theme {

    // Colors, 0xAARRGGBB.

    /** Ordinary text. */
    public static int TEXT = 0xFFFFFFFF;

    /** Secondary text: descriptions, what's off. */
    public static int MUTED = 0xFFB4BDC6;

    /** The accent: what's on, what's chosen, what's being edited. */
    public static int ACCENT = 0xFFA9DCFF;

    /** Good news. */
    public static int POSITIVE = 0xFF8FE3B0;

    /** Worth a look. */
    public static int WARNING = 0xFFFFD08A;

    /** Bad news. */
    public static int NEGATIVE = 0xFFFF8F8F;

    /** A panel behind text: the HUD's tinted glass (never blurred, so the frame costs nothing more). */
    public static int BACKGROUND = 0x6B283442;

    /** A panel in Normal: white glass over the blurred backdrop. */
    public static int GLASS = 0x2EFFFFFF;

    /** A panel in Potato: solid enough to read over an unblurred world. */
    public static int GLASS_SOLID = 0xDB242A32;

    /** A panel's edge, catching the light. */
    public static int GLASS_EDGE = 0x4DFFFFFF;

    /** A panel's edge in Potato. */
    public static int GLASS_EDGE_SOLID = 0x24FFFFFF;

    /** A button at rest, over either preset's backdrop. */
    public static int BUTTON = 0x1FFFFFFF;

    /** A button under the pointer or focused. */
    public static int BUTTON_HOT = 0x47FFFFFF;

    /** A button that can't be pressed. */
    public static int BUTTON_OFF = 0x0DFFFFFF;

    /** A text field's well, darker than the glass so typing stands out. */
    public static int FIELD = 0x59101820;

    /** A tooltip, popup or menu over everything else. */
    public static int FLOATING = 0xEB18202A;

    /**
     * The backdrop's cold tint over the blurred world or panorama (Normal), top and bottom: dark
     * enough that white text on the glass still reads over snow, sky or a lit room.
     */
    public static int TINT_TOP = 0x8C182433;

    public static int TINT_BOTTOM = 0xB3101822;

    /** The backdrop's tint in Potato, heavier: there's no blur to quiet the world behind. */
    public static int TINT_TOP_SOLID = 0xC2141B24;

    public static int TINT_BOTTOM_SOLID = 0xDE0E131A;

    /**
     * The HUD editor's tint over its blurred backdrop, top and bottom: lighter than a menu's, so the
     * world and the game's HUD can still be made out around what's being placed.
     */
    public static int EDITOR_TINT_TOP = 0x26101822;

    public static int EDITOR_TINT_BOTTOM = 0x4D101822;

    /** Text on an accent fill: a chosen element's name tag. */
    public static int ON_ACCENT = 0xFF0D2233;

    /** Whether HUD text has a drop shadow, as the game's HUD text does. */
    public static final boolean HUD_TEXT_SHADOW = true;

    // Metrics, in GUI units.

    /** A control's height: buttons, sliders, fields. */
    public static final int CONTROL_HEIGHT = 20;

    /** A setting's control's width. */
    public static final int CONTROL_WIDTH = 150;

    /** A small button's width (Reset, a module's settings). */
    public static final int SMALL_BUTTON_WIDTH = 50;

    /** A footer button's width. */
    public static final int FOOTER_BUTTON_WIDTH = 150;

    /** A list row's height, its control and the space below it. */
    public static final int ROW_HEIGHT = 24;

    /** A list's row width. */
    public static final int ROW_WIDTH = 340;

    /** Space between controls side by side. */
    public static final int GAP = 4;

    /** Space between a header's lines of text. */
    public static final int LINE_GAP = 4;

    /** A control's corner radius: buttons, fields, sliders. */
    public static int RADIUS = 3;

    /** A panel's corner radius. */
    public static int PANEL_RADIUS = 5;

    /** How far a hovered control's glow spreads, in Normal. */
    public static final int GLOW = 4;

    private Theme() {}

    /** The color the theme gives {@code tone}. */
    public static int color(Tone tone) {
        return switch (tone) {
            case TEXT -> TEXT;
            case MUTED -> MUTED;
            case ACCENT -> ACCENT;
            case POSITIVE -> POSITIVE;
            case WARNING -> WARNING;
            case NEGATIVE -> NEGATIVE;
            case BACKGROUND -> BACKGROUND;
            case EDGE -> GLASS_EDGE;
            case ON_ACCENT -> ON_ACCENT;
        };
    }

    // Frost's own, for restore().
    private static final int[] FROST = save();

    /** Every value apply() may change, in a fixed order. */
    private static int[] save() {
        return new int[] {
            TEXT,
            MUTED,
            ACCENT,
            POSITIVE,
            WARNING,
            NEGATIVE,
            BACKGROUND,
            GLASS,
            GLASS_SOLID,
            GLASS_EDGE,
            GLASS_EDGE_SOLID,
            BUTTON,
            BUTTON_HOT,
            BUTTON_OFF,
            FIELD,
            FLOATING,
            TINT_TOP,
            TINT_BOTTOM,
            TINT_TOP_SOLID,
            TINT_BOTTOM_SOLID,
            EDITOR_TINT_TOP,
            EDITOR_TINT_BOTTOM,
            ON_ACCENT,
            RADIUS,
            PANEL_RADIUS
        };
    }

    /** Frost, as the client draws it before the player picks a theme. */
    public static void restore() {
        int[] v = FROST;
        int i = 0;
        TEXT = v[i++];
        MUTED = v[i++];
        ACCENT = v[i++];
        POSITIVE = v[i++];
        WARNING = v[i++];
        NEGATIVE = v[i++];
        BACKGROUND = v[i++];
        GLASS = v[i++];
        GLASS_SOLID = v[i++];
        GLASS_EDGE = v[i++];
        GLASS_EDGE_SOLID = v[i++];
        BUTTON = v[i++];
        BUTTON_HOT = v[i++];
        BUTTON_OFF = v[i++];
        FIELD = v[i++];
        FLOATING = v[i++];
        TINT_TOP = v[i++];
        TINT_BOTTOM = v[i++];
        TINT_TOP_SOLID = v[i++];
        TINT_BOTTOM_SOLID = v[i++];
        EDITOR_TINT_TOP = v[i++];
        EDITOR_TINT_BOTTOM = v[i++];
        ON_ACCENT = v[i++];
        RADIUS = v[i++];
        PANEL_RADIUS = v[i];
    }

    /**
     * A theme from a few colors, each {@code 0xRRGGBB}: the accent, the text, the secondary text and
     * the panels' color. Every other shade comes from those as Frost's come from its own: the
     * buttons and edges are the text's color at Frost's alphas, the backdrops' tints and the
     * floating panels the panel color darkened. Frost's own colors give Frost exactly.
     *
     * @param panelOpacity the menus' panels' opacity in Potato, 0 to 100 (Frost: 86)
     * @param hudOpacity the HUD's glass's opacity, 0 to 100 (Frost: 42)
     * @param radius a control's corner radius in GUI units (Frost: 3); panels round 2 more
     */
    public static void apply(int accent, int text, int muted, int panel, int panelOpacity, int hudOpacity, int radius) {
        restore();
        if ((accent & 0xFFFFFF) == 0xA9DCFF
                && (text & 0xFFFFFF) == 0xFFFFFF
                && (muted & 0xFFFFFF) == 0xB4BDC6
                && (panel & 0xFFFFFF) == 0x242A32
                && panelOpacity == 86
                && hudOpacity == 42
                && radius == 3) {
            // Frost's own: its hand-picked shades, not ones made from these.
            return;
        }
        accent &= 0xFFFFFF;
        text &= 0xFFFFFF;
        muted &= 0xFFFFFF;
        panel &= 0xFFFFFF;
        ACCENT = 0xFF000000 | accent;
        TEXT = 0xFF000000 | text;
        MUTED = 0xFF000000 | muted;
        ON_ACCENT = luminance(accent) > 0.5F ? 0xFF000000 | darken(accent, 0.18F) : 0xFFFFFFFF;
        GLASS_SOLID = alpha(panelOpacity) | panel;
        BACKGROUND = alpha(hudOpacity) | lighten(panel, 0.04F);
        FLOATING = 0xEB000000 | darken(panel, 0.75F);
        FIELD = 0x59000000 | darken(panel, 0.5F);
        GLASS = 0x2E000000 | text;
        GLASS_EDGE = 0x4D000000 | text;
        GLASS_EDGE_SOLID = 0x24000000 | text;
        BUTTON = 0x1F000000 | text;
        BUTTON_HOT = 0x47000000 | text;
        BUTTON_OFF = 0x0D000000 | text;
        TINT_TOP = 0x8C000000 | darken(panel, 0.65F);
        TINT_BOTTOM = 0xB3000000 | darken(panel, 0.5F);
        TINT_TOP_SOLID = 0xC2000000 | darken(panel, 0.6F);
        TINT_BOTTOM_SOLID = 0xDE000000 | darken(panel, 0.45F);
        EDITOR_TINT_TOP = 0x26000000 | darken(panel, 0.5F);
        EDITOR_TINT_BOTTOM = 0x4D000000 | darken(panel, 0.5F);
        RADIUS = Math.max(0, radius);
        PANEL_RADIUS = Math.max(0, radius) + 2;
    }

    private static int alpha(int percent) {
        return Math.round(Math.max(0, Math.min(100, percent)) * 2.55F) << 24;
    }

    /** How light {@code rgb} looks, 0 to 1. */
    static float luminance(int rgb) {
        return (0.2126F * (rgb >> 16 & 0xFF) + 0.7152F * (rgb >> 8 & 0xFF) + 0.0722F * (rgb & 0xFF)) / 255;
    }

    /** {@code rgb} with each channel scaled by {@code keep}. */
    static int darken(int rgb, float keep) {
        int r = Math.round((rgb >> 16 & 0xFF) * keep);
        int g = Math.round((rgb >> 8 & 0xFF) * keep);
        int b = Math.round((rgb & 0xFF) * keep);
        return r << 16 | g << 8 | b;
    }

    /** {@code rgb} moved {@code by} of the way to white. */
    static int lighten(int rgb, float by) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        r += Math.round((255 - r) * by);
        g += Math.round((255 - g) * by);
        b += Math.round((255 - b) * by);
        return r << 16 | g << 8 | b;
    }
}
