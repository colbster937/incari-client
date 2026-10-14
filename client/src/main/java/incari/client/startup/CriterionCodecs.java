package incari.client.startup;

import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.advancements.triggers.CriterionTrigger;

/**
 * An advancement criterion's codec, built once per trigger instead of once per criterion. The game
 * decodes each criterion with a codec it makes on the spot from its trigger's ({@code xmap}), and
 * DataFixerUpper names every codec it makes eagerly, from the whole tree of codecs beneath it: a
 * string built and thrown away for each of the vanilla data pack's thousands of criteria. That was
 * 90 to 140 ms of each start of singleplayer's server on the development desktop, several times
 * that on the floor laptop. The codec depends on the trigger alone, so one per trigger does.
 *
 * <p>Data packs load on several threads: the map is concurrent, and a codec built twice by a race
 * is as good as the other.
 */
public final class CriterionCodecs {

    private static boolean enabled;
    private static final Map<CriterionTrigger<?>, Codec<?>> CODECS = new ConcurrentHashMap<>();

    private CriterionCodecs() {}

    /** Off unless the page turns it on ({@code ?criteria=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code Criterion.criterionCodec}): the criterion codec for {@code trigger}, made by {@code make}. */
    @SuppressWarnings("unchecked") // Each trigger's entry was made for it.
    public static <C> Codec<C> forTrigger(CriterionTrigger<?> trigger, Supplier<Codec<C>> make) {
        if (!enabled) {
            return make.get();
        }
        Codec<?> codec = CODECS.get(trigger);
        if (codec == null) {
            codec = make.get();
            CODECS.putIfAbsent(trigger, codec);
        }
        return (Codec<C>) codec;
    }
}
