package incari.client.startup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Over the current screen while a world waits for the models ({@link DeferredModels}): the screen
 * as it was, dimmed, with the game's "Loading Minecraft" over it, and no input reaching it. Usually
 * gone within a second of the title screen showing.
 */
final class ModelsOverlay extends Overlay {

    private static final Component LOADING = Component.translatable("gui.loadingMinecraft");

    private final Minecraft minecraft;

    ModelsOverlay(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Screen screen = minecraft.gui.screen();
        if (screen != null) {
            // No pointer: nothing under the overlay is hovered.
            screen.extractRenderStateWithTooltipAndSubtitles(graphics, -1, -1, a);
        }
        graphics.nextStratum();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xA0000000);
        graphics.centeredText(minecraft.font, LOADING, graphics.guiWidth() / 2, graphics.guiHeight() / 2, -1);
    }

    @Override
    public boolean isPausing() {
        return false;
    }
}
