package incari.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The look's panels and the color arithmetic its parts share: glass panels for the client's own
 * screens and cards, drawn as the skin draws the game's popups. Render thread; nothing allocates.
 */
public final class Panels {

    private Panels() {}

    /** A panel: glass with an edge and, in Normal, a soft shadow below. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fade) {
        boolean normal = Look.normal();
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.PANEL_RADIUS,
                alpha(normal ? Theme.GLASS : Theme.GLASS_SOLID, fade),
                alpha(normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID, fade),
                normal ? 6 : 0,
                2,
                alpha(0x40081420, fade));
    }

    /**
     * Blurs everything drawn before this stratum (the world, the HUD), at most once a frame: a
     * screen that frosts its backdrop in either preset ({@link Look#frost}).
     */
    public static void blurBehind(GuiGraphicsExtractor graphics) {
        FrostSkin.blur(graphics);
    }

    /** {@code argb} with its alpha scaled by {@code fade}. */
    public static int alpha(int argb, float fade) {
        if (fade >= 1.0F) {
            return argb;
        }
        int a = Math.round((argb >>> 24) * Math.max(0.0F, fade));
        return a << 24 | (argb & 0xFFFFFF);
    }

    /** The color {@code t} of the way from {@code from} to {@code to}, each channel. */
    public static int mix(int from, int to, float t) {
        if (t <= 0) {
            return from;
        }
        if (t >= 1) {
            return to;
        }
        int a = channel(from >>> 24, to >>> 24, t);
        int r = channel(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = channel(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /** A switch's size in GUI units. */
    public static final int SWITCH_WIDTH = 18;

    public static final int SWITCH_HEIGHT = 10;

    /**
     * An on/off switch at ({@code x}, {@code y}): an ice-blue track with a dark knob at the right
     * when on, a faint one with a grey knob at the left when off. In Normal the knob slides.
     */
    public static void toggle(GuiGraphicsExtractor graphics, int x, int y, boolean on, float fade) {
        float t = Motion.ease(Motion.key(x, y, SWITCH_WIDTH, -SWITCH_HEIGHT), on ? 1 : 0);
        int track = mix(0x2EFFFFFF, Theme.ACCENT, t);
        RoundRects.fill(graphics, x, y, SWITCH_WIDTH, SWITCH_HEIGHT, SWITCH_HEIGHT / 2, alpha(track, fade));
        int knob = SWITCH_HEIGHT - 2;
        int knobX = x + 1 + Math.round(t * (SWITCH_WIDTH - 2 - knob));
        RoundRects.fill(graphics, knobX, y + 1, knob, knob, knob / 2, alpha(mix(0xFFC9D1D9, 0xFF0D2233, t), fade));
    }

    /**
     * A card: a button-like glass tile that lights toward the pointer, edged in the accent while
     * {@code on}; in Normal, a glow under the pointer.
     */
    public static void card(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hot, boolean on, float fade) {
        float t = Motion.ease(Motion.key(x, y, width, height), hot ? 1 : 0);
        boolean normal = Look.normal();
        int edge = on ? Theme.ACCENT & 0x8CFFFFFF : normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID;
        if (!normal && hot) {
            edge = Theme.ACCENT;
        }
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.RADIUS + 1,
                alpha(mix(Theme.BUTTON, Theme.BUTTON_HOT, t * 0.7F), fade),
                alpha(edge, fade),
                normal && t > 0 ? Theme.GLOW : 0,
                0,
                alpha(Theme.ACCENT, 0.22F * t * fade));
    }

    /**
     * A dark panel, for what must read over a bright world in either preset: a container's slots and
     * labels. Edged and shadowed like {@link #panel}.
     */
    public static void darkPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fade) {
        boolean normal = Look.normal();
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.PANEL_RADIUS,
                alpha(normal ? 0xC21A2129 : Theme.GLASS_SOLID, fade),
                alpha(normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID, fade),
                normal ? 6 : 0,
                2,
                alpha(0x40081420, fade));
    }
}
