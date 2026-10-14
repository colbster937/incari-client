package incari.online;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every cosmetic this client can draw (ADR 0061), in the wardrobe's order. The service keeps the same
 * ids ({@code docs/online-protocol.md}); an id the service sends that isn't here (from a newer
 * client) is ignored, so a newer catalog never breaks an older client.
 *
 * <p>Immutable; any thread.
 */
public final class Catalog {

    private static final List<Cosmetic> ALL = List.of(
            // Capes: the client's own art (CapeArt), some of it animated.
            free("cape.frost", "Frost"),
            free("cape.aurora", "Aurora"),
            free("cape.midnight", "Midnight"),
            free("cape.ember", "Ember"),
            free("cape.sakura", "Sakura"),
            free("cape.tide", "Tide"),
            free("cape.mono", "Mono"),
            // Hats: small models on the head, hidden under a helmet.
            free("hat.crown", "Crown"),
            free("hat.halo", "Halo"),
            free("hat.top_hat", "Top Hat"),
            free("hat.beanie", "Beanie"),
            free("hat.horns", "Horns"),
            free("hat.cat_ears", "Cat Ears"),
            // On the back: hidden while an elytra is worn.
            free("back.angel_wings", "Angel Wings"),
            free("back.frost_wings", "Frost Wings"),
            free("back.bat_wings", "Bat Wings"),
            free("back.backpack", "Backpack"),
            // Name tags: the name's color over the head.
            free("nametag.ice", "Ice"),
            free("nametag.gold", "Gold"),
            free("nametag.mint", "Mint"),
            free("nametag.rose", "Rose"),
            free("nametag.violet", "Violet"),
            free("nametag.frost", "Frost Fade"),
            free("nametag.sunset", "Sunset Fade"),
            free("nametag.aurora", "Aurora Fade"),
            // Trails: a few particles behind the player as they move.
            free("trail.snow", "Snowflakes"),
            free("trail.hearts", "Hearts"),
            free("trail.sparkles", "Sparkles"),
            free("trail.notes", "Notes"),
            // Emotes: played from the emote wheel, seen by Incari players nearby.
            free("emote.wave", "Wave"),
            free("emote.clap", "Clap"),
            free("emote.cheer", "Cheer"),
            free("emote.salute", "Salute"),
            free("emote.facepalm", "Facepalm"),
            free("emote.shrug", "Shrug"),
            free("emote.dab", "Dab"),
            free("emote.bow", "Bow"));

    private static final Map<String, Cosmetic> BY_ID = new HashMap<>();
    private static final Map<Cosmetic.Slot, List<Cosmetic>> BY_SLOT = new EnumMap<>(Cosmetic.Slot.class);

    static {
        for (Cosmetic.Slot slot : Cosmetic.Slot.values()) {
            List<Cosmetic> inSlot = new ArrayList<>();
            for (Cosmetic each : ALL) {
                if (each.slot() == slot) {
                    inSlot.add(each);
                }
            }
            BY_SLOT.put(slot, Collections.unmodifiableList(inSlot));
        }
        for (Cosmetic each : ALL) {
            if (BY_ID.put(each.id(), each) != null) {
                throw new IllegalStateException("Two cosmetics are " + each.id());
            }
        }
    }

    private Catalog() {}

    private static Cosmetic free(String id, String name) {
        String prefix = id.substring(0, id.indexOf('.'));
        for (Cosmetic.Slot slot : Cosmetic.Slot.values()) {
            if (slot.prefix().equals(prefix)) {
                return new Cosmetic(id, slot, name, true);
            }
        }
        throw new IllegalArgumentException(id);
    }

    /** Every cosmetic, in the wardrobe's order. */
    public static List<Cosmetic> all() {
        return ALL;
    }

    /** The cosmetic with this id, or null if this client has none by it. */
    public static @Nullable Cosmetic byId(String id) {
        return BY_ID.get(id);
    }

    /** The cosmetics in {@code slot}, in the wardrobe's order. */
    public static List<Cosmetic> inSlot(Cosmetic.Slot slot) {
        return BY_SLOT.getOrDefault(slot, List.of());
    }
}
