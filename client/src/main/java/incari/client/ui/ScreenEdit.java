package incari.client.ui;

import net.minecraft.client.gui.screens.Screen;

/**
 * A change to one of the game's screens: widgets moved, resized, relabelled, removed or added. Runs
 * after each of the screen's layouts, so it states where things go, not how far to move them.
 *
 * @param <S> the screen's class
 */
@FunctionalInterface
public interface ScreenEdit<S extends Screen> {

    /** Makes the change; {@code screen}'s widgets are already laid out. Render thread. */
    void edit(EditedScreen<S> screen);
}
