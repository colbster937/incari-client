package incari.client.recorder;

import incari.client.IncariKeys;
import incari.client.settings.ClientSettings;
import incari.platform.api.PlatformRecorder;
import incari.platform.api.Platforms;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The screen recorder (ADR 0062), as Eaglercraft's browser builds have it: Record Screen in Incari
 * Settings, or its key (F8), starts recording the game's picture and sound (and the microphone, if
 * set); the same again stops, and the video downloads. While it records, a red "REC" and the time
 * show in the top left corner. The desktop has no recorder ({@link PlatformRecorder#NONE}): its
 * own screen recorders do this better.
 *
 * <p>Game and render thread (one thread in the page).
 */
public final class ScreenRecorder {

    /** Frames a second. */
    static final ClientSettings.Whole FPS = new ClientSettings.Whole("record-fps", 15, 60, 30);

    /** The video's bit rate, kilobits a second. */
    static final ClientSettings.Whole BITRATE = new ClientSettings.Whole("record-kbps", 1000, 16000, 2500);

    /** The game's sound in the recording, in percent. */
    static final ClientSettings.Whole GAME_VOLUME = new ClientSettings.Whole("record-game-volume", 0, 100, 100);

    /** The microphone's level in the recording, in percent. */
    static final ClientSettings.Whole MICROPHONE_VOLUME =
            new ClientSettings.Whole("record-microphone-volume", 0, 100, 100);

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

    private static final int RED = 0xFFFF4D4D;

    private static String shownError = "";

    private ScreenRecorder() {}

    /** Whether this platform records (the browser does). */
    public static boolean supported() {
        return recorder().supported();
    }

    /** Whether it's recording, or starting to. */
    public static boolean active() {
        return recorder().state() != PlatformRecorder.State.IDLE;
    }

    /** Starts recording, or stops and downloads what was recorded. */
    public static void toggle() {
        PlatformRecorder recorder = recorder();
        if (!recorder.supported()) {
            return;
        }
        if (recorder.state() != PlatformRecorder.State.IDLE) {
            recorder.stop();
            return;
        }
        recorder.start(
                new PlatformRecorder.Options(
                        FPS.get(),
                        BITRATE.get(),
                        GAME_VOLUME.get() / 100.0F,
                        ClientSettings.RECORD_MICROPHONE.get(),
                        MICROPHONE_VOLUME.get() / 100.0F),
                "incari-" + LocalDateTime.now(ZoneId.systemDefault()).format(FILE_TIME));
    }

    /** Why the last recording failed or went without the microphone; empty if it didn't. */
    static String error() {
        String error = recorder().error();
        return error == null ? "" : error;
    }

    /** Once a client tick: the key, and a failure told once in the chat's overlay. */
    public static void tick(Minecraft minecraft) {
        while (IncariKeys.RECORD_SCREEN.consumeClick()) {
            toggle();
        }
        String error = error();
        if (!error.equals(shownError)) {
            shownError = error;
            if (!error.isEmpty()) {
                minecraft.gui.chatListener().handleOverlay(Component.literal(error));
            }
        }
    }

    /** {@code Gui}, over everything: the red dot, "REC" and the time while recording. */
    public static void extract(GuiGraphicsExtractor graphics) {
        PlatformRecorder recorder = recorder();
        if (recorder.state() != PlatformRecorder.State.RECORDING) {
            return;
        }
        long seconds = recorder.elapsedMillis() / 1000;
        var font = Minecraft.getInstance().font;
        String text = String.format(Locale.ROOT, "REC %d:%02d", seconds / 60, seconds % 60);
        int width = font.width(text) + 16;
        graphics.fill(2, 2, 2 + width, 15, 0x90000000);
        // The dot blinks once a second, as a camera's does.
        if (recorder.elapsedMillis() % 1000 < 600) {
            graphics.fill(6, 6, 11, 11, RED);
        }
        graphics.text(font, text, 14, 5, 0xFFFFFFFF, false);
    }

    private static PlatformRecorder recorder() {
        return Platforms.current().recorder();
    }
}
