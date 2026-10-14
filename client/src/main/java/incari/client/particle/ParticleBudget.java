package incari.client.particle;

import incari.client.tier.DeviceTier;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * How many particles a particle group may hold (ADR 0024), by the device's tier: the game
 * allows 16384 in each group, which a Chromebook can't tick and draw at 60 frames a second. The
 * game's own scheme is kept, scaled to the cap: every particle is admitted until the group is three
 * quarters full, then fewer and fewer (with the square of the room left), and none past the cap.
 * At {@link DeviceTier#HIGH} it's exactly the game's.
 *
 * <p>The tier is the device's ({@link DeviceTier}) unless the page's {@code ?particles=potato} (or
 * standard, or high) chooses another for particles alone.
 */
public final class ParticleBudget {

    private static volatile @Nullable DeviceTier override;

    private ParticleBudget() {}

    /** The cap per particle group at {@code tier}. */
    public static int cap(DeviceTier tier) {
        return switch (tier) {
            case POTATO -> 2048;
            case STANDARD -> 8192;
            case HIGH -> 16384;
        };
    }

    public static DeviceTier tier() {
        DeviceTier chosen = override;
        return chosen != null ? chosen : DeviceTier.current();
    }

    /** Particles at {@code tier} whatever the device's is; null follows the device's again. */
    public static void setTier(@Nullable DeviceTier tier) {
        override = tier;
    }

    /**
     * Hook: whether a group holding {@code size} particles takes another. Draws from {@code random}
     * only in the last quarter, as the game does.
     */
    public static boolean admit(int size, RandomSource random) {
        int cap = cap(tier());
        if (size >= cap) {
            return false;
        }
        int reservoir = cap / 4;
        if (size >= cap - reservoir) {
            float room = (cap - size) / (float) reservoir;
            return random.nextFloat() < room * room;
        }
        return true;
    }
}
