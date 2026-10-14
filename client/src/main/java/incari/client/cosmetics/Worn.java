package incari.client.cosmetics;

import incari.online.Cosmetic;
import incari.online.Loadout;
import org.jspecify.annotations.Nullable;

/**
 * What one player shows, as their render state carries it (ADR 0061): what they wear, and the emote
 * they're playing, if any. Made when that changes, not each frame.
 *
 * <p>Immutable; render thread.
 */
public final class Worn {

    /** Nothing: what every player the client draws no cosmetics for carries. */
    public static final Worn NONE = new Worn(Loadout.EMPTY, null, 0);

    final Loadout loadout;
    final @Nullable Cosmetic cape;
    final @Nullable Cosmetic hat;
    final @Nullable Cosmetic back;
    final @Nullable Cosmetic nametag;
    final @Nullable Cosmetic trail;
    final @Nullable Cosmetic emote;
    /** When the emote started, in the player's own ticks ({@code ageInTicks}). */
    final float emoteStart;

    Worn(Loadout loadout, @Nullable Cosmetic emote, float emoteStart) {
        this.loadout = loadout;
        this.cape = loadout.in(Cosmetic.Slot.CAPE);
        this.hat = loadout.in(Cosmetic.Slot.HAT);
        this.back = loadout.in(Cosmetic.Slot.BACK);
        this.nametag = loadout.in(Cosmetic.Slot.NAMETAG);
        this.trail = loadout.in(Cosmetic.Slot.TRAIL);
        this.emote = emote;
        this.emoteStart = emoteStart;
    }

    /** Whether there's nothing to show. */
    boolean isEmpty() {
        return loadout.isEmpty() && emote == null;
    }
}
