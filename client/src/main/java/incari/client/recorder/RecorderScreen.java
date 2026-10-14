package incari.client.recorder;

import incari.client.IncariKeys;
import incari.client.settings.ClientSettings;
import incari.client.ui.ControlList;
import incari.client.ui.ListScreen;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The screen recorder's screen (ADR 0062): start and stop, and how it records (frame rate, quality,
 * the game's sound and the microphone). Recording goes on with the screen closed; the key does the
 * same as the button.
 */
public final class RecorderScreen extends ListScreen {

    /** Quality presets, kilobits a second: what a Chromebook encodes without dropping frames, up. */
    private static final List<Integer> QUALITIES = List.of(1500, 2500, 5000, 8000);

    private static final List<Integer> RATES = List.of(30, 60);

    private long revision;
    private @Nullable Button toggle;

    public RecorderScreen(@Nullable Screen parent) {
        super(Component.literal("Record Screen"), parent);
    }

    @Override
    protected Component caption() {
        String error = ScreenRecorder.error();
        if (!error.isEmpty()) {
            return Component.literal(error).withColor(Theme.WARNING);
        }
        return Component.literal("Records the game and its sound. The video downloads when you stop. "
                + IncariKeys.RECORD_SCREEN.getTranslatedKeyMessage().getString() + " also starts and stops it.");
    }

    @Override
    protected long revision() {
        return revision + (ScreenRecorder.active() ? 1L << 40 : 0);
    }

    @Override
    protected List<ControlList.Row> rows(ControlList list) {
        List<ControlList.Row> rows = new ArrayList<>();
        rows.add(list.row(
                Component.literal("Frame rate"),
                "Frames per second in the video.",
                List.of(Ui.choice(
                        Component.literal("Frame rate"),
                        RATES,
                        RATES.contains(ScreenRecorder.FPS.get()) ? ScreenRecorder.FPS.get() : 30,
                        rate -> Component.literal(rate + " fps"),
                        Theme.CONTROL_WIDTH,
                        rate -> ScreenRecorder.FPS.set(rate))),
                () -> {}));
        int bitrate = ScreenRecorder.BITRATE.get();
        rows.add(list.row(
                Component.literal("Quality"),
                "Higher is sharper but uses more memory while recording, about " + bitrate * 60 / 8 / 1000
                        + " MB a minute.",
                List.of(Ui.choice(
                        Component.literal("Quality"),
                        QUALITIES,
                        QUALITIES.contains(bitrate) ? bitrate : 2500,
                        kbps -> Component.literal(String.format(Locale.ROOT, "%.1f Mbit/s", kbps / 1000.0)),
                        Theme.CONTROL_WIDTH,
                        kbps -> {
                            ScreenRecorder.BITRATE.set(kbps);
                            revision++;
                        })),
                () -> {}));
        rows.add(list.row(
                Component.literal("Game sound"),
                "How loud the game is in the video compared to what you hear.",
                List.of(volume(ScreenRecorder.GAME_VOLUME)),
                () -> {}));
        rows.add(list.row(
                Component.literal("Microphone"),
                "Records your microphone too. Your browser asks for permission the first time.",
                List.of(Ui.toggle(
                        Component.literal("Microphone"),
                        ClientSettings.RECORD_MICROPHONE.get(),
                        Theme.CONTROL_WIDTH,
                        on -> {
                            ClientSettings.RECORD_MICROPHONE.set(on);
                            revision++;
                        })),
                () -> {}));
        if (ClientSettings.RECORD_MICROPHONE.get()) {
            rows.add(list.row(
                    Component.literal("Microphone volume"),
                    "",
                    List.of(volume(ScreenRecorder.MICROPHONE_VOLUME)),
                    () -> {}));
        }
        return rows;
    }

    @Override
    protected Object shape() {
        return ClientSettings.RECORD_MICROPHONE.get() + "/" + ScreenRecorder.BITRATE.get();
    }

    private static Ui.Slider volume(ClientSettings.Whole setting) {
        return Ui.slider(
                0,
                100,
                setting.get(),
                Theme.CONTROL_WIDTH,
                value -> Component.literal(Math.round(value) + "%"),
                value -> setting.set((int) Math.round(value)));
    }

    @Override
    protected void footer(LinearLayout footer) {
        Button button = Ui.button(label(), Theme.FOOTER_BUTTON_WIDTH, () -> {
            ScreenRecorder.toggle();
            refreshToggle();
        });
        toggle = button;
        footer.addChild(button);
        footer.addChild(Ui.button(CommonComponents.GUI_DONE, Theme.FOOTER_BUTTON_WIDTH, this::onClose));
    }

    @Override
    public void tick() {
        super.tick();
        refreshToggle();
    }

    private void refreshToggle() {
        Button button = toggle;
        if (button != null) {
            button.setMessage(label());
        }
    }

    private static Component label() {
        return Component.literal(ScreenRecorder.active() ? "Stop and Download" : "Start Recording");
    }

    @Override
    public boolean isPauseScreen() {
        // Recording is of the game: singleplayer keeps running while this is open.
        return false;
    }
}
