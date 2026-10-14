package incari.client.look;

import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.Skins;
import incari.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The HUD's text panels in the client's look, from hooks where the game fills them: the tab
 * list's header, players and footer as rounded tinted glass, the scoreboard sidebar on one card,
 * chat's lines on a cold tint, the chat field as a rounded well. Each returns false, for the game
 * to draw its own, when the look is off. No blur and no animation: the HUD is drawn into a texture
 * between refreshes in the browser (ADR 0030), and its cost is every frame's.
 *
 * <p>Render thread. Hot path: nothing allocates.
 */
public final class HudLook {

    /** Chat's lines: the game's background opacity over a cold dark instead of black. */
    private static final int CHAT_TINT = 0x0C1219;

    /** How far the scoreboard's card reaches past the game's box on each side. */
    private static final int SCOREBOARD_PAD = 2;

    private HudLook() {}

    /**
     * How far {@link #scoreboard}'s card reaches past the box it's given on each side: 0 when the
     * game draws its own. What places the sidebar measures the card, not the box.
     */
    public static int scoreboardPad() {
        return on() ? SCOREBOARD_PAD : 0;
    }

    private static boolean on() {
        return Skins.current() != null;
    }

    /**
     * {@code PlayerTabOverlay}: one of the tab list's boxes (header, players, footer), from
     * ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}). The game's boxes touch, so they're drawn
     * as pieces of one card: rounded and padded only at its {@code first} box's top and {@code
     * last} box's bottom, a hairline at each seam. Cards of their own would overlap there, and the
     * glass doubled into a dark band.
     */
    public static boolean tabBox(
            GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, boolean first, boolean last) {
        if (!on()) {
            return false;
        }
        int top = first ? y0 - 2 : y0;
        int bottom = last ? y1 + 2 : y1;
        if (first && last) {
            RoundRects.fill(graphics, x0 - 3, top, x1 - x0 + 6, bottom - top, Theme.RADIUS, Theme.BACKGROUND);
            return true;
        }
        // The card's straight ends reach past the seam, cut there.
        graphics.enableScissor(x0 - 3, top, x1 + 3, bottom);
        int reach = Theme.RADIUS + 1;
        int y = first ? top : top - reach;
        int height = (last ? bottom : bottom + reach) - y;
        RoundRects.fill(graphics, x0 - 3, y, x1 - x0 + 6, height, Theme.RADIUS, Theme.BACKGROUND);
        graphics.disableScissor();
        if (!first) {
            RoundRects.hairline(graphics, x0 + 1, y0, x1 - x0 - 2, 0x26FFFFFF);
        }
        return true;
    }

    /**
     * {@code PlayerTabOverlay}: a player's row in the list, left on the card's glass. The game's
     * rows are a unit apart, and a tint on each showed the gaps as dark lines.
     */
    public static boolean tabRow(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        return on();
    }

    /** {@code Hud.displayScoreboardSidebar}: the sidebar's card, its title row above a hairline. */
    public static boolean scoreboard(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int titleBottom) {
        if (!on()) {
            return false;
        }
        RoundRects.fill(
                graphics,
                x0 - SCOREBOARD_PAD,
                y0 - SCOREBOARD_PAD,
                x1 - x0 + 2 * SCOREBOARD_PAD,
                y1 - y0 + 2 * SCOREBOARD_PAD,
                Theme.RADIUS,
                Theme.BACKGROUND);
        RoundRects.hairline(graphics, x0 + 2, titleBottom, x1 - x0 - 4, 0x26FFFFFF);
        return true;
    }

    /** {@code ChatComponent}: a line's background, {@code argb} as the game would fill it. */
    public static int chatLine(int argb) {
        return on() ? (argb & 0xFF000000) | CHAT_TINT : argb;
    }

    /** {@code ChatScreen}: the field chat is typed into. */
    public static boolean chatField(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        if (!on()) {
            return false;
        }
        RoundRects.box(
                graphics,
                x0,
                y0 - 1,
                x1 - x0,
                y1 - y0 + 2,
                Theme.RADIUS,
                Panels.alpha(Theme.FLOATING, 0.85F),
                Theme.ACCENT & 0x66FFFFFF);
        return true;
    }
}
