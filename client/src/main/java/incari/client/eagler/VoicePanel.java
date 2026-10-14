package incari.client.eagler;

import incari.client.IncariKeys;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.ScreenPanels;
import incari.client.ui.Theme;
import incari.platform.api.rtc.Microphone;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Voice chat in the pause menu's top right corner, where Eagler's 1.8 client has it: the channel
 * (off, nearby or global) in one click, whether the microphone is live, who is connected and who is
 * talking, a click to mute anyone, and a link to the full settings ({@link VoiceScreen}). Shown on
 * servers with Eagler voice.
 *
 * <p>Laid out again every frame (the speakers come and go): its height follows its contents, and
 * its click targets are where they were last drawn.
 */
final class VoicePanel extends AbstractWidget {

    private static final int MAX_WIDTH = 150;
    private static final int MIN_WIDTH = 108;
    private static final int MARGIN = 6;
    private static final int PADDING = 6;
    private static final int ROW = 11;
    private static final int SEGMENT_HEIGHT = 14;
    private static final int MAX_SPEAKERS = 6;
    /** Half the game's pause menu, its buttons 204 wide, and the card's padding: where no card was laid out. */
    private static final int MENU_HALF_WIDTH = 114;
    /** The compact panel's margin to the screen's edge: it fits where the full one doesn't. */
    private static final int COMPACT_MARGIN = 3;

    private static final int COMPACT_PADDING = 4;

    private static final int BORDER = 0x1FFFFFFF;

    private static final VoiceSettings.Channel[] CHANNELS = {
        VoiceSettings.Channel.OFF, VoiceSettings.Channel.PROXIMITY, VoiceSettings.Channel.GLOBAL
    };

    private final Screen screen;
    private final EaglerVoice voice;
    private final Font font;
    /** What was drawn where, for clicks: each a rectangle and what a click on it does. */
    private final List<Target> targets = new ArrayList<>();

    private record Target(int x0, int y0, int x1, int y1, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private VoicePanel(Screen screen, EaglerVoice voice) {
        super(0, MARGIN, MAX_WIDTH, 40, Component.literal("Voice Chat"));
        this.screen = screen;
        this.voice = voice;
        this.font = Minecraft.getInstance().font;
    }

    /** {@code PauseScreen}: the panel, on a server with Eagler voice; else null. */
    static @Nullable VoicePanel create(Screen screen) {
        EaglerVoice voice = EaglerVoice.current();
        return voice == null || !voice.available() ? null : new VoicePanel(screen, voice);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        targets.clear();
        // Beside the menu's card, never over it: where the full panel doesn't fit, a small one that
        // opens the voice settings.
        int cardRight = ScreenPanels.right(screen);
        int room = (cardRight < 0 ? screen.width - (screen.width / 2 + MENU_HALF_WIDTH) : screen.width - cardRight)
                - MARGIN * 2;
        if (room < MIN_WIDTH) {
            extractCompact(graphics, mouseX, mouseY);
            return;
        }
        int width = Math.min(MAX_WIDTH, room);
        setWidth(width);
        setY(MARGIN);
        setX(screen.width - width - MARGIN);
        int x = getX() + PADDING;
        int right = getRight() - PADDING;
        VoiceSettings.Channel channel = EaglerVoice.settings().channel();
        boolean on = channel != VoiceSettings.Channel.OFF;

        int top = getY();
        List<EaglerVoice.Speaker> speakers = on ? voice.speakers() : List.of();
        setHeight(height(on, speakers.size()));
        Panels.panel(graphics, getX(), top, getWidth(), getHeight(), 1.0F);

        int y = top + PADDING;
        graphics.text(font, "Voice Chat", x, y, Theme.TEXT);
        extractState(graphics, right, y, on);
        y += ROW + 3;

        y = extractChannels(graphics, x, right, y, channel, mouseX, mouseY);
        y += 4;

        if (on) {
            Status status = status();
            graphics.text(font, font.plainSubstrByWidth(status.text, right - x), x, y, status.color);
            y += ROW + 2;
            graphics.fill(x, y, right, y + 1, BORDER);
            y += 4;
            y = extractSpeakers(graphics, speakers, x, right, y, mouseX, mouseY);
        } else {
            graphics.text(font, "Pick a channel to talk", x, y, Theme.MUTED);
            y += ROW;
        }

        y += 2;
        String settings = "Settings...";
        int settingsWidth = font.width(settings);
        boolean settingsHovered = mouseX >= x && mouseX < x + settingsWidth && mouseY >= y - 1 && mouseY < y + 9;
        graphics.text(font, settings, x, y, settingsHovered ? Theme.TEXT : Theme.MUTED);
        if (settingsHovered) {
            graphics.fill(x, y + 9, x + settingsWidth, y + 10, Theme.TEXT);
        }
        targets.add(new Target(
                x,
                y - 1,
                x + settingsWidth,
                y + 10,
                () -> Minecraft.getInstance().gui.setScreen(new VoiceScreen(screen, voice))));
    }

    /**
     * The panel in a narrow window: a dot that says whether voice is on, and "Voice", which opens the
     * voice settings. Small enough for the room beside the card at the smallest window and largest
     * GUI scale.
     */
    private void extractCompact(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String label = "Voice";
        int width = COMPACT_PADDING + 7 + font.width(label) + COMPACT_PADDING;
        int height = 9 + COMPACT_PADDING * 2;
        setWidth(width);
        setHeight(height);
        setX(screen.width - width - COMPACT_MARGIN);
        setY(COMPACT_MARGIN);
        boolean hovered = isMouseOver(mouseX, mouseY);
        Panels.panel(graphics, getX(), getY(), width, height, 1.0F);
        boolean on = EaglerVoice.settings().channel() != VoiceSettings.Channel.OFF;
        int color = on ? Theme.POSITIVE : Theme.MUTED;
        // The dot pulses while the player talks, as the full panel's does.
        boolean lit = !voice.transmitting() || (Util.getMillis() / 400) % 2 == 0;
        int x = getX() + COMPACT_PADDING;
        int y = getY() + COMPACT_PADDING;
        graphics.fill(x, y + 2, x + 4, y + 6, lit ? color : (color & 0x00FFFFFF) | 0x60000000);
        graphics.text(font, label, x + 7, y, hovered ? Theme.TEXT : Theme.MUTED);
        targets.add(new Target(
                getX(),
                getY(),
                getRight(),
                getBottom(),
                () -> Minecraft.getInstance().gui.setScreen(new VoiceScreen(screen, voice))));
    }

    /** The panel's height: what {@link #extractWidgetRenderState} draws, row by row. */
    private static int height(boolean on, int speakers) {
        int height = PADDING + ROW + 3 + SEGMENT_HEIGHT + 4;
        if (on) {
            height += ROW + 2 + 4;
            height += speakers == 0 ? ROW : ROW + 1 + Math.min(speakers, MAX_SPEAKERS) * ROW;
            height += speakers > MAX_SPEAKERS ? ROW : 0;
        } else {
            height += ROW;
        }
        return height + 2 + ROW + PADDING - 2;
    }

    /** A dot and a word at the top right: off, live, or talking now. */
    private void extractState(GuiGraphicsExtractor graphics, int right, int y, boolean on) {
        String word;
        int color;
        if (!on) {
            word = "Off";
            color = Theme.MUTED;
        } else if (voice.transmitting()) {
            word = "Talking";
            color = Theme.POSITIVE;
        } else {
            word = "On";
            color = Theme.POSITIVE;
        }
        int wordX = right - font.width(word);
        graphics.text(font, word, wordX, y, color);
        // The dot pulses while the player talks.
        boolean lit = !voice.transmitting() || (Util.getMillis() / 400) % 2 == 0;
        int dotX = wordX - 7;
        graphics.fill(dotX, y + 2, dotX + 4, y + 6, lit ? color : (color & 0x00FFFFFF) | 0x60000000);
    }

    /** Off, Nearby and Global as one segmented control; returns the y below it. */
    private int extractChannels(
            GuiGraphicsExtractor graphics,
            int x,
            int right,
            int y,
            VoiceSettings.Channel chosen,
            int mouseX,
            int mouseY) {
        int segmentWidth = (right - x) / CHANNELS.length;
        for (int i = 0; i < CHANNELS.length; i++) {
            VoiceSettings.Channel channel = CHANNELS[i];
            int x0 = x + i * segmentWidth;
            int x1 = i == CHANNELS.length - 1 ? right : x0 + segmentWidth;
            boolean selected = channel == chosen;
            boolean hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + SEGMENT_HEIGHT;
            int fill = selected
                    ? (channel == VoiceSettings.Channel.OFF ? Theme.BUTTON_HOT : (Theme.ACCENT & 0x59FFFFFF))
                    : hovered ? Theme.BUTTON : 0x14FFFFFF;
            RoundRects.fill(graphics, x0 + 1, y, x1 - x0 - 2, SEGMENT_HEIGHT, Theme.RADIUS, fill);
            if (selected) {
                RoundRects.outline(
                        graphics,
                        x0 + 1,
                        y,
                        x1 - x0 - 2,
                        SEGMENT_HEIGHT,
                        Theme.RADIUS,
                        channel == VoiceSettings.Channel.OFF ? BORDER : Theme.ACCENT);
            }
            String label = label(channel);
            graphics.text(
                    font,
                    label,
                    (x0 + x1 - font.width(label)) / 2,
                    y + (SEGMENT_HEIGHT - 8) / 2,
                    selected || hovered ? Theme.TEXT : Theme.MUTED);
            if (!selected) {
                targets.add(new Target(x0, y, x1, y + SEGMENT_HEIGHT, () -> voice.setChannel(channel)));
            }
        }
        return y + SEGMENT_HEIGHT;
    }

    /** Everyone connected, talking ones lit, each with a mute toggle; returns the y below them. */
    private int extractSpeakers(
            GuiGraphicsExtractor graphics,
            List<EaglerVoice.Speaker> speakers,
            int x,
            int right,
            int y,
            int mouseX,
            int mouseY) {
        if (speakers.isEmpty()) {
            graphics.text(font, "No one else connected", x, y, Theme.MUTED);
            return y + ROW;
        }
        graphics.text(font, "Connected (" + speakers.size() + ")", x, y, Theme.MUTED);
        y += ROW + 1;
        for (EaglerVoice.Speaker speaker : speakers.stream().limit(MAX_SPEAKERS).toList()) {
            String toggle = speaker.muted() ? "Muted" : "Mute";
            int toggleX = right - font.width(toggle);
            boolean hovered = mouseX >= toggleX - 2 && mouseX < right && mouseY >= y - 1 && mouseY < y + 9;
            int dot = speaker.muted() ? Theme.NEGATIVE : speaker.talking() ? Theme.POSITIVE : 0xFF505058;
            graphics.fill(x, y + 2, x + 4, y + 6, dot);
            String name = font.plainSubstrByWidth(speaker.name(), toggleX - x - 12);
            graphics.text(
                    font,
                    name,
                    x + 7,
                    y,
                    speaker.muted() ? Theme.MUTED : speaker.talking() ? Theme.POSITIVE : Theme.TEXT);
            graphics.text(
                    font, toggle, toggleX, y, speaker.muted() ? Theme.NEGATIVE : hovered ? Theme.TEXT : Theme.MUTED);
            UUID uuid = speaker.uuid();
            boolean muted = speaker.muted();
            targets.add(new Target(toggleX - 2, y - 1, right, y + 9, () -> voice.setMuted(uuid, !muted)));
            y += ROW;
        }
        if (speakers.size() > MAX_SPEAKERS) {
            graphics.text(font, "+" + (speakers.size() - MAX_SPEAKERS) + " more in Settings", x, y, Theme.MUTED);
            y += ROW;
        }
        return y;
    }

    private record Status(String text, int color) {}

    private Status status() {
        Microphone.State microphone = voice.microphone();
        String key = IncariKeys.PUSH_TO_TALK.getTranslatedKeyMessage().getString();
        return switch (microphone) {
            case OPEN ->
                voice.transmitting()
                        ? new Status("Talking...", Theme.POSITIVE)
                        : new Status("Hold " + key + " to talk", Theme.MUTED);
            case OPENING, CLOSED -> new Status("Waiting for microphone", Theme.WARNING);
            case UNAVAILABLE -> new Status("No mic: listening only", Theme.NEGATIVE);
        };
    }

    private static String label(VoiceSettings.Channel channel) {
        return switch (channel) {
            case OFF -> "Off";
            case PROXIMITY -> "Nearby";
            case GLOBAL -> "Global";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isMouseOver(event.x(), event.y()) || !isValidClickButton(event.buttonInfo())) {
            return false;
        }
        for (Target target : List.copyOf(targets)) {
            if (target.contains(event.x(), event.y())) {
                Minecraft.getInstance()
                        .getSoundManager()
                        .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                target.action.run();
                return true;
            }
        }
        // A click on the panel's background goes nowhere else.
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
