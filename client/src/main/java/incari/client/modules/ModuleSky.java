package incari.client.modules;

import incari.framework.game.Weather;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The sky as a module shows it ({@code Game.overrideSky}, Weather Changer): the weather and the
 * time of day on this client only. What draws and plays the weather, and the clock's getters, ask
 * here as they're read, so the server's own weather and clock are untouched and come back the
 * moment the override is cleared; the level's weather itself stays the server's for the game's own
 * decisions. Render and game thread; the hooks are hot, so each is a field read while nothing is
 * overridden.
 */
public final class ModuleSky {

    /** Ticks in a day: the overworld's clock. */
    private static final long DAY = 24000;

    private static @Nullable Weather weather;
    private static int timeOfDay = -1;

    private ModuleSky() {}

    /** {@code Game.overrideSky}'s: null and -1 give the world's own back. */
    static void override(@Nullable Weather chosen, int time) {
        weather = chosen;
        timeOfDay = time < 0 ? -1 : (int) Math.floorMod(time, DAY);
    }

    /** From leaving a world: the next world starts with its own sky. */
    static void leave() {
        weather = null;
        timeOfDay = -1;
    }

    /**
     * Hook (where the rain is drawn or heard: {@code WeatherEffectRenderer}, {@code SkyRenderer},
     * the fog, the weather's environment attributes, {@code ClientLevel.tickWeatherEffects}): the
     * rain as shown. The level's own {@code getRainLevel} stays the server's, so what the game
     * decides by the weather (a trident's Riptide, fire put out) goes by the real one. A level
     * without weather (the Nether, the End) shows none; the server's own levels show theirs.
     */
    public static float shownRain(Level level, float partialTick) {
        Weather shown = weather;
        if (shown == null || !level.isClientSide() || !level.canHaveWeather()) {
            return level.getRainLevel(partialTick);
        }
        return shown == Weather.CLEAR ? 0 : 1;
    }

    /** Hook (as {@link #shownRain}): the thunder as shown. */
    public static float shownThunder(Level level, float partialTick) {
        Weather shown = weather;
        if (shown == null || !level.isClientSide() || !level.canHaveWeather()) {
            return level.getThunderLevel(partialTick);
        }
        return shown == Weather.THUNDER ? 1 : 0;
    }

    /**
     * Hook ({@code WeatherEffectRenderer}, {@code ClientLevel.tickWeatherEffects}): what falls in a
     * column, snow everywhere for Snow (so no rain's splashes or sound either).
     */
    public static Biome.Precipitation precipitation(Biome.Precipitation biome) {
        return weather == Weather.SNOW ? Biome.Precipitation.SNOW : biome;
    }

    /**
     * Hook ({@code ClientClockManager.ClientClockInstance.totalTicks}): the clock's ticks, at the
     * chosen time of the same day if it's the level's day clock.
     */
    public static long clockTicks(ClientClockManager.ClientClockInstance clock, long ticks) {
        if (timeOfDay < 0 || !isDayClock(clock)) {
            return ticks;
        }
        return Math.floorDiv(ticks, DAY) * DAY + timeOfDay;
    }

    /** Hook ({@code ClientClockInstance.partialTick}): none while the time is held, so it stands still. */
    public static float clockPartialTick(ClientClockManager.ClientClockInstance clock, float partial) {
        return timeOfDay < 0 || !isDayClock(clock) ? partial : 0;
    }

    @SuppressWarnings("ReferenceEquality") // The clock manager keeps one instance per clock.
    private static boolean isDayClock(ClientClockManager.ClientClockInstance clock) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return level.dimensionType()
                .defaultClock()
                .map(definition -> level.clockManager().getInstance(definition) == clock)
                .orElse(false);
    }
}
