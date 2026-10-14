package incari.client.look;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * What the title screen shows behind its menu: the menus' photo ({@link incari.client.ui.MenuBackdrop}) unless
 * something else is set ({@link TitleLook#backdrop}).
 */
@FunctionalInterface
public interface TitleBackdrop {

    /** Draws the backdrop over the whole screen, {@code width} by {@code height} GUI units. */
    void extract(GuiGraphicsExtractor graphics, int width, int height, float a);
}
