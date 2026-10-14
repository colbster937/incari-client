package incari.client.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import incari.client.tier.DeviceTier;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;

/**
 * How much decoded sound the platform's cache keeps (ADR 0025). The game keeps every sound it
 * has decoded until the next resource reload; the browser's are 32-bit float samples at the output
 * rate, about 190 kB a second of mono sound, held by the page outside the JavaScript heap but in the
 * tab's memory all the same. Past the tier's budget, the sounds played longest ago leave the cache:
 * one still playing finishes (its source holds the samples), and the next time it's played it's
 * decoded again from the file, which the service worker keeps.
 */
final class DecodedSoundBudget {

    /** Decoded sounds by when they were last asked for, oldest first; sizes in bytes. */
    private final LinkedHashMap<Identifier, Integer> sizes = new LinkedHashMap<>(64, 0.75f, true);

    private long total;

    /** The bytes of decoded sound kept at {@code tier}. */
    static long budget(DeviceTier tier) {
        return switch (tier) {
            case POTATO -> 16L << 20;
            case STANDARD -> 32L << 20;
            case HIGH -> 96L << 20;
        };
    }

    /** {@code location} was asked for again. */
    synchronized void used(Identifier location) {
        sizes.get(location);
    }

    /** {@code location} finished decoding into {@code bytes} bytes. */
    synchronized void decoded(Identifier location, int bytes) {
        Integer previous = sizes.put(location, bytes);
        total += bytes - (previous == null ? 0 : previous);
    }

    /** Bytes of decoded sound counted now. */
    synchronized long total() {
        return total;
    }

    /**
     * Drops the sounds asked for longest ago from {@code cache} until what's left is within
     * {@code budget}, never {@code keep} (the sound being asked for now).
     */
    synchronized void trim(Map<Identifier, CompletableFuture<SoundBuffer>> cache, Identifier keep, long budget) {
        Iterator<Map.Entry<Identifier, Integer>> oldest = sizes.entrySet().iterator();
        while (total > budget && oldest.hasNext()) {
            Map.Entry<Identifier, Integer> entry = oldest.next();
            if (entry.getKey().equals(keep)) {
                continue;
            }
            oldest.remove();
            total -= entry.getValue();
            // Gone already if the game cleared its cache (a resource reload).
            cache.remove(entry.getKey());
        }
    }
}
