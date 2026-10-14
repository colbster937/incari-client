package incari.online;

/**
 * One cosmetic in Incari's catalog (ADR 0061): something an account wears that other Incari players
 * see (a cape, a hat, a name tag's color), or an emote it can play. The client draws each from its
 * own art; the service only keeps which ids an account owns and wears.
 *
 * @param id the id the service and other clients know it by, {@code <slot>.<name>}; never changes
 * @param slot where it's worn: one cosmetic per slot
 * @param name what the wardrobe calls it
 * @param free whether every account owns it
 */
public record Cosmetic(String id, Slot slot, String name, boolean free) {

    /** Where a cosmetic is worn. An account wears at most one per slot; emotes are played, not worn. */
    public enum Slot {
        CAPE("cape", "Capes"),
        HAT("hat", "Hats"),
        BACK("back", "Wings & Back"),
        NAMETAG("nametag", "Name Tags"),
        TRAIL("trail", "Trails"),
        EMOTE("emote", "Emotes");

        private final String prefix;
        private final String label;

        Slot(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        /** The ids' prefix: {@code cape} in {@code cape.frost}. */
        public String prefix() {
            return prefix;
        }

        /** What the wardrobe's tab says. */
        public String label() {
            return label;
        }

        /** Whether a cosmetic in this slot is worn (and sent with {@code equip}); emotes aren't. */
        public boolean worn() {
            return this != EMOTE;
        }
    }

    public Cosmetic {
        if (!id.startsWith(slot.prefix() + ".")) {
            throw new IllegalArgumentException(id + " isn't in slot " + slot);
        }
    }
}
