package incari.client.cosmetics;

import incari.online.Cosmetic;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Name tag cosmetics (ADR 0061): the name over a player's head in a color, or fading from one color
 * to another across it. Only where the server leaves the name uncolored: a team's or a rank's color
 * means something in the game, and wins.
 *
 * <p>Render thread. Each player's last name tag and its styled copy are kept, so a name that doesn't
 * change is styled once.
 */
public final class NameTags {

    /** Each player's last name tag in, and what it became. */
    private record Styled(Component from, Cosmetic style, Component to) {}

    private static final Map<UUID, Styled> LAST = new HashMap<>();

    private NameTags() {}

    /** The colors of {@code style}: one for a plain color, two for a fade. */
    public static int[] colors(Cosmetic style) {
        return switch (style.id()) {
            case "nametag.ice" -> new int[] {0xA9DCFF};
            case "nametag.gold" -> new int[] {0xFFD36A};
            case "nametag.mint" -> new int[] {0x8FE3B0};
            case "nametag.rose" -> new int[] {0xFF9BB8};
            case "nametag.violet" -> new int[] {0xC6A2FF};
            case "nametag.frost" -> new int[] {0xFFFFFF, 0x7FC8FF};
            case "nametag.sunset" -> new int[] {0xFFB36A, 0xFF6FA8};
            case "nametag.aurora" -> new int[] {0x6AF2B0, 0xA06CFF};
            default -> new int[] {0xFFFFFF};
        };
    }

    /** {@code tag} styled for the player {@code id}, or {@code tag} itself where the server colored it. */
    static Component style(UUID id, Component tag, Cosmetic style) {
        Styled last = LAST.get(id);
        if (last != null && last.style().equals(style) && last.from().equals(tag)) {
            return last.to();
        }
        Component styled = colored(tag) ? tag : styled(tag.getString(), style);
        LAST.put(id, new Styled(tag, style, styled));
        return styled;
    }

    /** {@code name} in {@code style}: for the wardrobe's preview and cards, too. */
    public static Component styled(String name, Cosmetic style) {
        int[] colors = colors(style);
        if (colors.length == 1 || name.length() < 2) {
            return Component.literal(name).withColor(colors[0]);
        }
        MutableComponent out = Component.empty();
        int last = name.length() - 1;
        for (int i = 0; i < name.length(); i++) {
            int color = CosmeticArt.mix(colors[0], colors[1], i / (float) last);
            out.append(Component.literal(String.valueOf(name.charAt(i))).withColor(color));
        }
        return out;
    }

    /** Whether any part of {@code tag} has a color of the server's. */
    private static boolean colored(Component tag) {
        Optional<Boolean> found = tag.visit(
                (Style style, String text) ->
                        style.getColor() != null && !text.isEmpty() ? Optional.of(Boolean.TRUE) : Optional.empty(),
                Style.EMPTY);
        return found.isPresent();
    }

    /** Players who left, forgotten (on leaving a world). */
    static void clear() {
        LAST.clear();
    }
}
