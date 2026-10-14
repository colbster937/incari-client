package incari.client.eagler;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Who is talking, in the HUD's top left corner, from one hook in {@code Hud}. Shown only
 * while someone is, or the player is.
 */
public final class VoiceOverlay {

    private static final int MAX_SHOWN = 5;

    private VoiceOverlay() {}

    public static void extract(GuiGraphicsExtractor graphics) {
        EaglerVoice voice = EaglerVoice.current();
        if (voice == null || !voice.available() || EaglerVoice.settings().channel() == VoiceSettings.Channel.OFF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int y = 4;
        if (voice.transmitting()) {
            graphics.text(minecraft.font, "▶ " + minecraft.getUser().getName(), 4, y, 0xFF55FF55);
            y += 10;
        }
        List<EaglerVoice.Speaker> talking = voice.speakers().stream()
                .filter(EaglerVoice.Speaker::talking)
                .limit(MAX_SHOWN)
                .toList();
        for (EaglerVoice.Speaker speaker : talking) {
            graphics.text(minecraft.font, "▶ " + speaker.name(), 4, y, 0xFFFFFFFF);
            y += 10;
        }
    }
}
