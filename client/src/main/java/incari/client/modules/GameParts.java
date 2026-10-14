package incari.client.modules;

import incari.client.look.HudLook;
import incari.framework.ModuleSystem;
import incari.framework.game.GamePart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

/**
 * Parts of the game's HUD a module draws itself ({@code GamePartElement}, ADR 0050): the scoreboard
 * sidebar. The game still draws the sidebar, with its own code and the client's look, but from the
 * module's HUD element and at its place: {@link #takeSidebar} keeps the game from drawing it where
 * it would, {@link #drawSidebar} has it drawn at the element's origin, and {@link #sidebarBox},
 * inside the game's drawing, moves it there and measures it. Render thread.
 */
public final class GameParts {

    /** The sidebar the game would have drawn this frame, if a module took it. */
    private static @Nullable Objective sidebar;

    /** Whether the game is drawing the sidebar for a module's element (move it to the origin). */
    private static boolean placing;

    /** The sidebar's size as last drawn, in GUI units. */
    private static int sidebarWidth;

    private static int sidebarHeight;

    private static @Nullable Objective sample;

    private GameParts() {}

    /**
     * Hook ({@code Hud.extractScoreboardSidebar}): the sidebar the game should draw in its place,
     * or null when a module draws it.
     */
    public static @Nullable Objective takeSidebar(@Nullable Objective objective) {
        ModuleSystem system = IncariModules.current();
        if (system == null || !system.holds(GamePart.SCOREBOARD)) {
            sidebar = null;
            return objective;
        }
        sidebar = objective;
        return null;
    }

    /**
     * Hook ({@code Hud.displayScoreboardSidebar}, before it draws): the sidebar's box as the game
     * places it. When a module's element is drawing it, the box is moved to the element's origin.
     */
    public static void sidebarBox(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        if (!placing) {
            return;
        }
        // The card drawn around the box (HudLook) is what the element places and must fit.
        int pad = HudLook.scoreboardPad();
        sidebarWidth = right - left + 2 * pad;
        sidebarHeight = bottom - top + 2 * pad;
        graphics.pose().translate(pad - left, pad - top);
    }

    /** The player left the world: its sidebar goes with it. */
    static void leave() {
        sidebar = null;
        sidebarWidth = 0;
        sidebarHeight = 0;
    }

    /** Whether there's a sidebar to draw: the server's, or the editor's sample. */
    static boolean hasSidebar(boolean editing) {
        return sidebar != null || editing;
    }

    /** The sidebar's width as last drawn; 0 before the first. */
    static int sidebarWidth(boolean editing) {
        if (sidebarWidth == 0 && hasSidebar(editing)) {
            measure(editing);
        }
        return hasSidebar(editing) ? sidebarWidth : 0;
    }

    /** The sidebar's height as last drawn. */
    static int sidebarHeight(boolean editing) {
        if (sidebarHeight == 0 && hasSidebar(editing)) {
            measure(editing);
        }
        return hasSidebar(editing) ? sidebarHeight : 0;
    }

    /** Draws the sidebar with its top left at {@code x, y} (already in the element's frame). */
    static void drawSidebar(GuiGraphicsExtractor graphics, int x, int y, boolean editing) {
        Objective objective = sidebar;
        if (objective == null) {
            if (!editing) {
                return;
            }
            objective = sample();
        }
        graphics.pose().pushMatrix().translate(x, y);
        placing = true;
        try {
            Minecraft.getInstance().gui.hud.displayScoreboardSidebar(graphics, objective);
        } finally {
            placing = false;
            graphics.pose().popMatrix();
        }
    }

    /**
     * A size before the first draw, which measures it: the element is placed by its size before
     * it's drawn, so the first frame goes where a sidebar of the game's usual shape would.
     */
    private static void measure(boolean editing) {
        // A sidebar's lines are 9 units, the title's one more.
        sidebarWidth = 80;
        sidebarHeight = 9 * (editing && sidebar == null ? 4 : 6);
    }

    /** A sidebar for the HUD editor while the server sends none. */
    private static Objective sample() {
        Objective current = sample;
        if (current == null) {
            Scoreboard scoreboard = new Scoreboard();
            current = scoreboard.addObjective(
                    "incari_sample",
                    ObjectiveCriteria.DUMMY,
                    Component.literal("Scoreboard"),
                    ObjectiveCriteria.RenderType.INTEGER,
                    false,
                    null);
            String[] lines = {"Kills", "Deaths", "Coins"};
            int[] scores = {12, 3, 250};
            for (int i = 0; i < lines.length; i++) {
                scoreboard
                        .getOrCreatePlayerScore(ScoreHolder.forNameOnly(lines[i]), current)
                        .set(scores[i]);
            }
            sample = current;
        }
        return current;
    }
}
