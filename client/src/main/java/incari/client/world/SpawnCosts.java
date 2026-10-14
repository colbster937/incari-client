package incari.client.world;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.timeline.Timeline;
import org.slf4j.Logger;

/**
 * A level's natural mob spawn settings, worked out once per biome (Lithium's cached spawn
 * settings, in spirit). Every tick, to share out the mob cap, the game asks for the spawn settings
 * at each counted mob's block: the attribute system finds the block's biome, then applies the
 * biome's settings over the dimension's, building a new settings object where they differ. In
 * the singleplayer benchmark's idle spawn area that was over half the server worker's tick, with
 * spawning switched off.
 *
 * <p>A level's attribute system gives these settings from layers: the dimension's (constant), the
 * biomes' (by position, from the block's biome alone, at full resolution), the dimension's
 * timelines and the weather (by time). The weather never changes these settings. If none of the
 * dimension's timelines does either, the answer depends on the block's biome and nothing else, so
 * the answer for one block of a biome is the answer for all of them. Otherwise, or once something
 * replaces the attribute system it first saw on the level (a game test's timelines), it asks the
 * system as the game does.
 *
 * <p>Server thread only: each level is ticked on it.
 */
public final class SpawnCosts {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean enabled;
    private static boolean verify;
    private static long checked;
    private static long differed;

    /** Per level: the settings by biome, while the level keeps the attribute system they came from. */
    private static final Map<ServerLevel, ByBiome> LEVELS = new WeakHashMap<>();

    private SpawnCosts() {}

    /** Off unless the page turns it on ({@code ?spawncosts=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Also asks the attribute system each time and logs where its spawn costs differ from the
     * cached ones ({@code ?spawncosts=verify}).
     */
    public static void setVerify(boolean on) {
        verify = on;
    }

    /** Hook ({@code NaturalSpawner.createState}): the natural mob spawn settings at {@code pos}. */
    public static MobSpawnSettings naturalMobSpawns(ServerLevel level, BlockPos pos) {
        EnvironmentAttributeSystem attributes = level.environmentAttributes();
        if (!enabled) {
            return attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
        }
        ByBiome byBiome = LEVELS.computeIfAbsent(level, ByBiome::new);
        if (!byBiome.applies(attributes)) {
            return attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
        }
        // The biome layer's own lookup: a block's centre, floored, at full resolution.
        Holder<Biome> biome = level.getBiomeManager().getBiome(pos);
        MobSpawnSettings settings = byBiome.settings.get(biome);
        if (settings == null) {
            settings = attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
            byBiome.settings.put(biome, settings);
        }
        if (verify) {
            verify(attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos), settings, pos);
        }
        return settings;
    }

    private static void verify(MobSpawnSettings game, MobSpawnSettings cached, BlockPos pos) {
        checked++;
        if (!game.allSpawnCosts().equals(cached.allSpawnCosts())) {
            differed++;
            if (differed <= 10) {
                LOGGER.warn(
                        "[Incari] Spawn costs differ at {}: the game's {}, cached {}",
                        pos,
                        game.allSpawnCosts(),
                        cached.allSpawnCosts());
            }
        }
        if (checked % 20_000 == 0) {
            LOGGER.info("[Incari] Spawn costs checked {} times, {} different", checked, differed);
        }
    }

    /** Whether the attribute's value at a block depends on nothing but the block's biome, in {@code level}. */
    static boolean byBiomeAlone(ServerLevel level) {
        for (Holder<Timeline> timeline : level.dimensionType().timelines()) {
            if (timeline.value().attributes().contains(EnvironmentAttributes.NATURAL_MOB_SPAWNS)) {
                return false;
            }
        }
        return true;
    }

    private static final class ByBiome {

        /** The attribute system first seen on the level, the one {@link #byBiomeAlone} describes. */
        private final EnvironmentAttributeSystem system;

        private final boolean byBiome;
        /** Biome holders are unique per registry entry (and compare by identity). */
        final Map<Holder<Biome>, MobSpawnSettings> settings = new HashMap<>();

        ByBiome(ServerLevel level) {
            system = level.environmentAttributes();
            byBiome = byBiomeAlone(level);
        }

        // The same system, not an equal one: a replaced system may hold other layers.
        @SuppressWarnings("ReferenceEquality")
        boolean applies(EnvironmentAttributeSystem current) {
            return byBiome && current == system;
        }
    }
}
