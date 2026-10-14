package incari.client.modules;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.client.settings.ClientSettings;
import incari.framework.ModuleSystem;
import incari.framework.event.Events;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Screenshots (ADR 0054; the gallery is the client's own since ADR 0062, not a module's): the
 * gallery, F2's picture on the clipboard when Incari Settings says so, the screenshot event for
 * modules, and a saved picture put on the clipboard.
 */
public final class ModuleScreenshots {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ModuleScreenshots() {}

    /**
     * Hook ({@code Screenshot.grab}, after the file is written, on the I/O pool): the event, on the
     * game thread.
     */
    public static void saved(File file) {
        String name = file.getName();
        Minecraft.getInstance().execute(() -> {
            if (ClientSettings.COPY_SCREENSHOTS.get()) {
                copy(folder().resolve(name));
            }
            ModuleSystem system = IncariModules.current();
            if (system != null && system.events().count(Events.SCREENSHOT) > 0) {
                system.events().fireScreenshot(name);
            }
        });
    }

    /** The screenshots folder. */
    static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    /** Puts the picture at {@code path} on the clipboard; false if the platform can't. */
    static boolean copy(Path path) {
        try {
            return ClientPlatform.get().shell().setClipboardImage(Files.readAllBytes(path));
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", path, e.toString());
            return false;
        }
    }

    /** Opens the gallery over {@code parent} (null for the game). */
    public static void openGallery(@Nullable Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ScreenshotsScreen(parent, minecraft.gameDirectory.toPath()));
    }
}
