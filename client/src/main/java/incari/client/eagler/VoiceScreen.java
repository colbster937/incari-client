package incari.client.eagler;

import incari.client.IncariKeys;
import incari.platform.api.rtc.Microphone;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Voice chat's settings and the voices connected now, opened from the pause menu's voice
 * panel ({@link VoicePanel}) on a server with Eagler voice. Mutes last for the connection.
 *
 * <p>English only for now; Incari's screens don't have language files yet.
 */
public final class VoiceScreen extends Screen {

    private static final int MAX_LISTED = 8;

    private final Screen parent;
    private final EaglerVoice voice;

    public VoiceScreen(Screen parent, EaglerVoice voice) {
        super(Component.literal("Voice Chat"));
        this.parent = parent;
        this.voice = voice;
    }

    @Override
    protected void init() {
        VoiceSettings settings = EaglerVoice.settings();
        int x = this.width / 2 - 155;
        int y = 40;
        this.addRenderableWidget(CycleButton.<VoiceSettings.Channel>builder(
                        VoiceScreen::channelName, settings.channel())
                .withValues(List.of(VoiceSettings.Channel.values()))
                .create(x, y, 150, 20, Component.literal("Channel"), (button, channel) -> voice.setChannel(channel)));
        this.addRenderableWidget(slider(
                x + 160,
                y,
                settings.range(),
                VoiceSettings.MIN_RANGE,
                VoiceSettings.MAX_RANGE,
                value -> "Proximity: " + Math.round(value) + " blocks",
                value -> voice.setRange((int) Math.round(value))));
        this.addRenderableWidget(slider(
                x,
                y + 24,
                settings.listenVolume(),
                0,
                2,
                value -> "Voices: " + Math.round(value * 100) + "%",
                value -> voice.setListenVolume((float) value)));
        this.addRenderableWidget(slider(
                x + 160,
                y + 24,
                settings.microphoneVolume(),
                0,
                2,
                value -> "Microphone: " + Math.round(value * 100) + "%",
                value -> voice.setMicrophoneVolume((float) value)));
        int row = y + 72;
        for (EaglerVoice.Speaker speaker :
                voice.speakers().stream().limit(MAX_LISTED).toList()) {
            this.addRenderableWidget(Button.builder(Component.literal(speaker.muted() ? "Unmute" : "Mute"), button -> {
                        voice.setMuted(speaker.uuid(), !speaker.muted());
                        this.rebuildWidgets();
                    })
                    .bounds(x + 250, row, 60, 18)
                    .build());
            row += 20;
        }
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    private static Component channelName(VoiceSettings.Channel channel) {
        return Component.literal(
                switch (channel) {
                    case OFF -> "Off";
                    case GLOBAL -> "Global";
                    case PROXIMITY -> "Nearby";
                });
    }

    private static AbstractSliderButton slider(
            int x, int y, double value, double min, double max, DoubleFunction<String> label, DoubleConsumer set) {
        return new AbstractSliderButton(x, y, 150, 20, Component.empty(), (value - min) / (max - min)) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label.apply(min + this.value * (max - min))));
            }

            @Override
            protected void applyValue() {
                set.accept(min + this.value * (max - min));
            }
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        int x = this.width / 2 - 155;
        graphics.text(this.font, status(), x, 90, 0xFFFFFFFF);
        int row = 112 + 5;
        for (EaglerVoice.Speaker speaker :
                voice.speakers().stream().limit(MAX_LISTED).toList()) {
            Component name = Component.literal(speaker.name())
                    .withStyle(speaker.talking() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
            graphics.text(this.font, name, x, row, 0xFFFFFFFF);
            row += 20;
        }
    }

    private Component status() {
        if (EaglerVoice.settings().channel() == VoiceSettings.Channel.OFF) {
            return Component.literal("Voice is off. Pick a channel to turn on your microphone.")
                    .withStyle(ChatFormatting.GRAY);
        }
        Microphone.State microphone = voice.microphone();
        String key = IncariKeys.PUSH_TO_TALK.getTranslatedKeyMessage().getString();
        return switch (microphone) {
            case OPEN -> Component.literal("Hold " + key + " to talk." + (voice.transmitting() ? " Talking." : ""));
            case OPENING, CLOSED ->
                Component.literal("Waiting for the microphone...").withStyle(ChatFormatting.YELLOW);
            case UNAVAILABLE ->
                Component.literal("No microphone found. You can listen but not talk.")
                        .withStyle(ChatFormatting.RED);
        };
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
