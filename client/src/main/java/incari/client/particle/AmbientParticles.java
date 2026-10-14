package incari.client.particle;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The ambient particles a position may spawn, worked out once per biome a tick (ADR 0031) instead
 * of once per position. Each tick {@code ClientLevel.animateTick} samples 1334 random positions
 * around the player and asks each for its ambient particles: the dimension's value, then the
 * biome's modifier, then the time of day and the weather, run through every time. In a crowded
 * scene that was a tenth of the page's work.
 *
 * <p>Exact: the attribute's only layer that reads the position is the biome's
 * ({@code EnvironmentAttributeSystem.addBiomeLayerForAttribute}), which looks the biome up as
 * {@link net.minecraft.world.level.biome.BiomeManager#getNoiseBiomeAtPosition(BlockPos)} does, and
 * the other layers (constants, the clock, the weather) don't change within one tick. So two
 * positions of one biome get the same value, and the first is kept for the rest of the tick. The
 * biome is still looked up per position.
 *
 * <p>Render thread only (the game ticks there).
 */
public final class AmbientParticles {

    /** Biomes kept a tick: a few at most are within the sampled 32 blocks; more go the game's way. */
    private static final int SLOTS = 8;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final @Nullable Holder<Biome>[] BIOMES = new Holder[SLOTS];

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final List<AmbientParticle>[] VALUES = new List[SLOTS];

    private static int kept;

    private static volatile boolean enabled = true;

    private AmbientParticles() {}

    /** Whether values are kept (the page's {@code ?ambient=game} turns it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook (start of {@code ClientLevel.animateTick}): a new tick, whose values aren't known yet. */
    public static void beginTick() {
        for (int i = 0; i < kept; i++) {
            BIOMES[i] = null;
            VALUES[i] = List.of();
        }
        kept = 0;
    }

    /**
     * Hook ({@code ClientLevel.doAnimateTick}): what
     * {@code level.environmentAttributes().getValue(EnvironmentAttributes.AMBIENT_PARTICLES, pos)}
     * gives.
     */
    @SuppressWarnings("ReferenceEquality") // A biome's holder is the registry's one instance.
    public static List<AmbientParticle> at(ClientLevel level, BlockPos pos) {
        if (!enabled || EnvironmentAttributes.AMBIENT_PARTICLES.isFullResolutionBiomes()) {
            return level.environmentAttributes().getValue(EnvironmentAttributes.AMBIENT_PARTICLES, pos);
        }
        Holder<Biome> biome = level.getBiomeManager().getNoiseBiomeAtPosition(pos);
        for (int i = 0; i < kept; i++) {
            if (BIOMES[i] == biome) {
                return VALUES[i];
            }
        }
        List<AmbientParticle> value =
                level.environmentAttributes().getValue(EnvironmentAttributes.AMBIENT_PARTICLES, pos);
        keep(biome, value);
        return value;
    }

    private static void keep(Holder<Biome> biome, List<AmbientParticle> value) {
        if (kept < SLOTS) {
            BIOMES[kept] = biome;
            VALUES[kept] = value;
            kept++;
        }
    }
}
