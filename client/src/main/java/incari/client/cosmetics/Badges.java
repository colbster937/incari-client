package incari.client.cosmetics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Incari's badge beside an Incari player's name tag and their name in the tab list (ADR 0062), so
 * Incari players know each other without asking. The badge is one glyph of Incari's pack's {@code incari:badge} font (Incari's
 * mark at 32 pixels, white so the glyph keeps its own colours), so the game's own name tag and tab list drawing carry it:
 * nothing new is drawn. Shown for players whose account lets it show, while the player's
 * "Show Incari badges" switch is on.
 *
 * <p>Render thread. A badged name is made once per player and name, not each frame; name tags and
 * the tab list keep their own, as the two names differ.
 */
final class Badges {

    private static final Component BADGE = Component.literal("")
            .withStyle(Style.EMPTY
                    .withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("incari", "badge")))
                    .withColor(0xFFFFFF));

    private record Badged(Component from, Component to) {}

    private static final Map<UUID, Badged> NAME_TAGS = new HashMap<>();
    private static final Map<UUID, Badged> TAB_LIST = new HashMap<>();

    private Badges() {}

    /** The name tag {@code tag} with the badge before it, for the player {@code id}. */
    static Component badged(UUID id, Component tag) {
        return badged(NAME_TAGS, id, tag);
    }

    /** The tab list's {@code name} with the badge before it, for the player {@code id}. */
    static Component badgedInTabList(UUID id, Component name) {
        return badged(TAB_LIST, id, name);
    }

    private static Component badged(Map<UUID, Badged> made, UUID id, Component name) {
        Badged last = made.get(id);
        if (last != null && last.from().equals(name)) {
            return last.to();
        }
        MutableComponent badged = Component.empty().append(BADGE).append(" ").append(name);
        if (made.size() > 512) {
            made.clear();
        }
        made.put(id, new Badged(name, badged));
        return badged;
    }

    /** Players who left, forgotten (on leaving a world). */
    static void clear() {
        NAME_TAGS.clear();
        TAB_LIST.clear();
    }
}
