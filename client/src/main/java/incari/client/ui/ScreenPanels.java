package incari.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Glass panels behind a screen's widgets, drawn with its backdrop (so under every widget): the
 * pause menu's card, laid out by an edit ({@link ScreenEdits}) after each layout. Only the shown
 * screen's panels are kept.
 *
 * <p>Render thread. Hot path: a fixed array, nothing allocates.
 */
public final class ScreenPanels {

    private static final int MAX = 8;

    private static @Nullable Screen owner;
    private static final int[] rects = new int[MAX * 4];
    private static int count;

    private ScreenPanels() {}

    /** Forgets {@code screen}'s panels: an edit calls this before laying them out again. */
    public static void clear(Screen screen) {
        owner = screen;
        count = 0;
    }

    /** A panel behind {@code screen}'s widgets, from now until its next {@link #clear}. */
    @SuppressWarnings("ReferenceEquality") // the shown screen object, not an equal one
    public static void add(Screen screen, int x, int y, int width, int height) {
        if (owner != screen) {
            clear(screen);
        }
        if (count < MAX) {
            rects[count * 4] = x;
            rects[count * 4 + 1] = y;
            rects[count * 4 + 2] = width;
            rects[count * 4 + 3] = height;
            count++;
        }
    }

    /**
     * The right edge of {@code screen}'s panels, furthest out; -1 if it has none: what's beside the
     * pause menu's card keeps clear of it.
     */
    @SuppressWarnings("ReferenceEquality") // the shown screen object, not an equal one
    public static int right(Screen screen) {
        if (screen != owner) {
            return -1;
        }
        int right = -1;
        for (int i = 0; i < count; i++) {
            right = Math.max(right, rects[i * 4] + rects[i * 4 + 2]);
        }
        return right;
    }

    /** Draws {@code screen}'s panels, if it has any. */
    @SuppressWarnings("ReferenceEquality") // the shown screen object, not an equal one
    static void extract(GuiGraphicsExtractor graphics, @Nullable Screen screen) {
        if (screen == null || screen != owner) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Panels.panel(graphics, rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3], 1.0F);
        }
    }
}
