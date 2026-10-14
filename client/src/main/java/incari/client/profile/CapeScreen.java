package incari.client.profile;

import com.mojang.blaze3d.Blaze3D;
import incari.client.skins.OwnSkin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The cape this player shows on Eagler servers and in their own view: none, or a 64x32 PNG.
 * Opened from Edit Profile's Capes link. The file arrives as a skin does: the button opens the
 * skin folder (the file picker in the browser) and the file dropped on the window, or picked, is
 * taken.
 */
final class CapeScreen extends Screen {

    private final Screen parent;
    private final OwnSkin skin;
    private Component message = CommonComponents.EMPTY;

    CapeScreen(Screen parent, OwnSkin skin) {
        super(Component.literal("Capes"));
        this.parent = parent;
        this.skin = skin;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 30;
        this.addRenderableWidget(Button.builder(Component.literal("Choose Cape File..."), button -> pick())
                .bounds(x, y, 98, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("No Cape"), button -> {
                    skin.removeCape();
                    message = Component.literal("No cape.");
                })
                .bounds(x + 102, y, 98, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y + 48, 200, 20)
                .build());
    }

    /** Opens the skin folder (the browser's file picker); the file comes back through a drop. */
    private void pick() {
        Path folder = this.minecraft.gameDirectory.toPath().resolve("incari").resolve("skin");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            message = Component.literal("Couldn't create " + folder).withStyle(ChatFormatting.RED);
            return;
        }
        message = Component.literal("Drop a 64x32 cape PNG on the window.");
        Blaze3D.openFolderToAddFiles(folder);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        Path file = files.get(0);
        try {
            skin.chooseCapeFile(file);
            message = Component.literal("Cape set from " + file.getFileName() + ".");
        } catch (IOException e) {
            message = Component.literal("Can't use " + file.getFileName() + ": " + e.getMessage())
                    .withStyle(ChatFormatting.RED);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        Component current = Component.literal(skin.hasCape() ? "You wear your own cape." : "You wear no cape.");
        graphics.centeredText(this.font, current, this.width / 2, this.height / 2 - 50, 0xFFA0A0A0);
        graphics.centeredText(this.font, message, this.width / 2, this.height / 2 - 2, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
