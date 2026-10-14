package incari.client.ui;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's look: Incari's skin over the game's GUI, and the player's {@link GuiPreset},
 * kept in {@code incari/look.txt}. Potato until the player picks Normal: new installs start on the
 * Potato graphics preset too, and the GUI shouldn't spend what the world saved.
 *
 * <p>Render and game thread (one thread on both platforms).
 */
public final class Look {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "look.txt";

    /** The menu blur's radius in Normal when the game's own blur option is lower (Potato graphics sets 0). */
    private static final int NORMAL_BLUR = 5;

    private static GuiPreset preset = GuiPreset.POTATO;
    private static @Nullable Path file;
    /** Whether a screen blurs its backdrop in either preset (the HUD editor), until it closes. */
    private static boolean frosted;

    private Look() {}

    /** From the first client tick: reads the saved preset and puts Incari's skin on the GUI. */
    public static void start(Minecraft minecraft) {
        if (file != null) {
            return;
        }
        Path path = minecraft.gameDirectory.toPath().resolve("incari").resolve(FILE);
        file = path;
        preset = read(path);
        Skins.use(FrostSkin.INSTANCE);
        LOGGER.info("Look: Frost, {} preset", preset.name().toLowerCase(Locale.ROOT));
    }

    public static GuiPreset preset() {
        return preset;
    }

    /** Whether the GUI's costly effects are on: blur, animation, light sweeps. */
    public static boolean normal() {
        return preset == GuiPreset.NORMAL;
    }

    /** Switches to {@code chosen} from the next frame, and saves it. */
    public static void set(GuiPreset chosen) {
        preset = chosen;
        Motion.reset();
        Path path = file;
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "preset=" + chosen.name().toLowerCase(Locale.ROOT) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", path, e.toString());
        }
    }

    /**
     * Blurs the backdrop in Potato too while {@code on}: the HUD editor's, where the blur is what
     * sets the HUD apart from the world. It keeps the cost down itself ({@code BackdropCache}).
     */
    public static void frost(boolean on) {
        frosted = on;
    }

    /**
     * {@code GameRenderer}'s menu blur radius: the game's option, or at least Normal's own when the
     * Normal preset (or a {@link #frost frosted} screen) blurs the backdrop: Potato graphics turns
     * the game's blur off.
     */
    public static int blurRadius(int option) {
        return normal() || frosted ? Math.max(option, NORMAL_BLUR) : option;
    }

    private static GuiPreset read(Path path) {
        if (!Files.isRegularFile(path)) {
            return GuiPreset.POTATO;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split > 0 && line.substring(0, split).trim().equals("preset")) {
                    try {
                        return GuiPreset.valueOf(
                                line.substring(split + 1).trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        // A hand-edited line; keep the default.
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", path, e.toString());
        }
        return GuiPreset.POTATO;
    }
}
