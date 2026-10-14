package incari.client.ui;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Changes to the game's own screens without a patch for each: {@link #edit} rearranges a screen's
 * widgets, removes some or adds Incari's, and {@link #replace} shows a screen of Incari's in place
 * of one of the game's. This is how the restyle reaches the title screen, the pause menu and
 * the options, as {@link Ui} and {@link Theme} reach Incari's own screens.
 *
 * <p>Two hooks call in: {@code Screen} after each layout (opening, a resize, a rebuild) and {@code
 * Gui.setScreen} before a screen is shown. Edits match a screen's exact class, not its subclasses,
 * since a change made for one layout is wrong for another. A screen that lays itself out again
 * without a rebuild, such as the statistics screen changing tab, puts moved widgets back until its
 * next layout. An edit or a replacement that throws is logged and skipped, and the screen is shown
 * as the game made it.
 *
 * <p>Render thread. With nothing registered, each hook costs a map check.
 */
public final class ScreenEdits {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, List<ScreenEdit<?>>> EDITS = new HashMap<>();
    private static final Map<Class<?>, Function<Screen, Screen>> REPLACEMENTS = new HashMap<>();

    private ScreenEdits() {}

    /** Makes {@code edit} to every {@code type} screen after each of its layouts, after earlier edits. */
    public static <S extends Screen> void edit(Class<S> type, ScreenEdit<? super S> edit) {
        EDITS.computeIfAbsent(type, _ -> new ArrayList<>()).add(edit);
    }

    /**
     * Shows {@code replacement}'s screen whenever the game would show a {@code type} screen. The
     * game's screen is made but never shown; the replacement gets it, to take its parent or its
     * callback from. Code that looks for the game's class (such as {@code instanceof TitleScreen})
     * then finds the replacement instead: check where the game does that before replacing one.
     *
     * @throws IllegalStateException if {@code type} already has a replacement
     */
    @SuppressWarnings("unchecked") // only ever called with a screen of class S
    public static <S extends Screen> void replace(Class<S> type, Function<? super S, ? extends Screen> replacement) {
        if (REPLACEMENTS.containsKey(type)) {
            throw new IllegalStateException(type.getSimpleName() + " already has a replacement");
        }
        REPLACEMENTS.put(type, screen -> replacement.apply((S) screen));
    }

    /** {@code Gui.setScreen}: the screen to show for {@code screen}. */
    public static @Nullable Screen replaced(@Nullable Screen screen) {
        if (screen == null || REPLACEMENTS.isEmpty()) {
            return screen;
        }
        Function<Screen, Screen> replacement = REPLACEMENTS.get(screen.getClass());
        if (replacement == null) {
            return screen;
        }
        try {
            return replacement.apply(screen);
        } catch (RuntimeException e) {
            LOGGER.error(
                    "Couldn't replace {}; showing the game's", screen.getClass().getSimpleName(), e);
            return screen;
        }
    }

    /**
     * {@code Screen}, after a layout: removes what the last edits added, then makes the screen's
     * edits again.
     *
     * @param added the widgets edits added to this screen, kept by the screen
     */
    @SuppressWarnings("unchecked") // EDITS holds only edits for the class they're stored under
    public static void laidOut(
            Screen screen,
            List<GuiEventListener> added,
            Consumer<AbstractWidget> add,
            Consumer<GuiEventListener> remove) {
        if (!added.isEmpty()) {
            for (GuiEventListener widget : added) {
                remove.accept(widget);
            }
            added.clear();
        }
        if (EDITS.isEmpty()) {
            return;
        }
        List<ScreenEdit<?>> edits = EDITS.get(screen.getClass());
        if (edits == null) {
            return;
        }
        EditedScreen<Screen> edited = new EditedScreen<>(screen, added, add, remove);
        for (ScreenEdit<?> edit : edits) {
            try {
                ((ScreenEdit<Screen>) edit).edit(edited);
            } catch (RuntimeException e) {
                LOGGER.error("An edit to {} failed", screen.getClass().getSimpleName(), e);
            }
        }
    }
}
