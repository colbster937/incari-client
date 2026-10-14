package incari.client.online;

import incari.client.ui.Circles;
import incari.client.ui.Theme;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The chat menu's pictures of people (ADR 0059): a player on this server shows their skin's face,
 * as the tab list does; anyone else (a friend elsewhere, the player's own account) a disc in a
 * colour of their name with its first letter. Render thread.
 */
final class Avatars {

    /** Discs' colours: calm, readable under white text, apart from the accent and the warnings. */
    private static final int[] COLORS = {
        0xFF4E6E9E, 0xFF5A8F7B, 0xFF8A6BB0, 0xFFB0735A, 0xFF4F8FA8, 0xFF9E5A78, 0xFF6F8A4E, 0xFF7A6E5A
    };

    static final int ONLINE = 0xFF5ED38A;
    static final int OFFLINE = 0xFF7D8590;

    private Avatars() {}

    /** The skin of the player called {@code name} on this server, or null. */
    static @Nullable PlayerSkin skin(String name) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return null;
        }
        PlayerInfo info = connection.getPlayerInfo(name);
        return info == null ? null : info.getSkin();
    }

    /** {@code label}'s picture, {@code size} square at ({@code x}, {@code y}). */
    static void draw(
            GuiGraphicsExtractor graphics, Font font, int x, int y, int size, String label, @Nullable PlayerSkin skin) {
        if (skin != null) {
            PlayerFaceExtractor.extractRenderState(graphics, skin, x, y, size);
            return;
        }
        float radius = size / 2.0F;
        Circles.disc(graphics, x + radius, y + radius, radius, color(label));
        String letter = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.ROOT);
        graphics.text(font, letter, x + (size - font.width(letter)) / 2 + 1, y + (size - 7) / 2, Theme.TEXT, false);
    }

    /** A presence dot on an avatar's lower right corner, ringed in {@code behind} to stand off it. */
    static void status(GuiGraphicsExtractor graphics, int x, int y, int size, boolean online, int behind) {
        float cx = x + size - 2.5F;
        float cy = y + size - 2.5F;
        Circles.disc(graphics, cx, cy, 3.5F, behind);
        Circles.disc(graphics, cx, cy, 2.5F, online ? ONLINE : OFFLINE);
    }

    /** {@code label}'s colour, lightened for their name over the dark glass. */
    static int lighter(String label) {
        int color = color(label);
        int r = (color >> 16 & 0xFF) + (255 - (color >> 16 & 0xFF)) / 2;
        int g = (color >> 8 & 0xFF) + (255 - (color >> 8 & 0xFF)) / 2;
        int b = (color & 0xFF) + (255 - (color & 0xFF)) / 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int color(String label) {
        return COLORS[Math.floorMod(label.toLowerCase(Locale.ROOT).hashCode(), COLORS.length)];
    }
}
