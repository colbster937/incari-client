package incari.client.cosmetics;

import incari.client.IncariKeys;
import incari.client.online.IncariOnline;
import incari.client.ui.Circles;
import incari.client.ui.Look;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.online.Cosmetic;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The emote wheel (ADR 0061): held open by its key (G), the player's emotes round a circle; the one
 * the pointer points toward plays when the key is let go, or when it's clicked. Escape, or letting
 * go with the pointer in the middle, plays nothing. A quick tap of the key, let go before the wheel
 * opened, leaves it open to click an emote; the key again closes it. The game goes on behind it.
 */
public final class EmoteWheelScreen extends Screen {

    private static final float RADIUS = 78;
    private static final float INNER = 24;
    private static final float LABEL_RADIUS = 54;

    private final List<Cosmetic> emotes;
    private int hovered = -1;

    /**
     * Whether the key was let go before the wheel opened (it opens on the tick after the press): its
     * release is gone, so the wheel waits for a click instead.
     */
    private final boolean tapped;

    public EmoteWheelScreen() {
        super(Component.literal("Emotes"));
        this.emotes = IncariOnline.service().wardrobe().emotes();
        this.tapped = !IncariKeys.EMOTE_WHEEL.isDown();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        hovered = pointedAt(mouseX, mouseY);
        Circles.disc(graphics, cx, cy, RADIUS, Look.normal() ? 0xB0101822 : Theme.GLASS_SOLID);
        Circles.ring(graphics, cx, cy, RADIUS, 1, Theme.GLASS_EDGE);
        Circles.ring(graphics, cx, cy, INNER, 1, Theme.GLASS_EDGE);
        int count = emotes.size();
        for (int i = 0; i < count; i++) {
            double angle = angle(i, count);
            int x = Math.round(cx + (float) Math.sin(angle) * LABEL_RADIUS);
            int y = Math.round(cy - (float) Math.cos(angle) * LABEL_RADIUS);
            Component name = Component.literal(emotes.get(i).name());
            int textWidth = font.width(name);
            boolean on = i == hovered;
            if (on) {
                RoundRects.fill(graphics, x - textWidth / 2 - 6, y - 8, textWidth + 12, 16, 8, Theme.ACCENT);
            }
            graphics.text(font, name, x - textWidth / 2, y - 4, on ? Theme.ON_ACCENT : Theme.TEXT, false);
        }
        Component middle = hovered >= 0
                ? Component.literal(emotes.get(hovered).name())
                : Component.literal(count == 0 ? "No emotes" : "Emotes");
        graphics.centeredText(font, middle, (int) cx, (int) cy - 4, hovered >= 0 ? Theme.ACCENT : Theme.MUTED);
        String key = IncariKeys.EMOTE_WHEEL.getTranslatedKeyMessage().getString();
        Component hint = Component.literal(
                tapped
                        ? "Click an emote to play it, or press " + key + " to close"
                        : "Release " + key + " or click to play");
        graphics.centeredText(font, hint, (int) cx, (int) (cy + RADIUS + 10), Theme.MUTED);
    }

    /** The angle of emote {@code i} of {@code count}, clockwise from straight up. */
    private static double angle(int i, int count) {
        return Math.PI * 2 * i / Math.max(1, count);
    }

    /** The emote the pointer points toward, or -1 in the middle or with none. */
    private int pointedAt(double mouseX, double mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        if (emotes.isEmpty() || dx * dx + dy * dy < INNER * INNER) {
            return -1;
        }
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) {
            angle += Math.PI * 2;
        }
        double slice = Math.PI * 2 / emotes.size();
        return (int) Math.round(angle / slice) % emotes.size();
    }

    private void play(int index) {
        onClose();
        if (index >= 0 && index < emotes.size()) {
            Cosmetics.playEmote(emotes.get(index));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tapped && IncariKeys.EMOTE_WHEEL.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (!tapped && IncariKeys.EMOTE_WHEEL.matches(event)) {
            play(hovered);
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        play(pointedAt(event.x(), event.y()));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The world stays as it is behind the wheel: the player is in the middle of a game.
    }

    /** The emote under the pointer, for tests. */
    @Nullable
    Cosmetic hovered() {
        return hovered >= 0 && hovered < emotes.size() ? emotes.get(hovered) : null;
    }
}
