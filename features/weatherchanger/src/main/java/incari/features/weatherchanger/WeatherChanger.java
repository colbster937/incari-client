package incari.features.weatherchanger;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.game.Weather;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.IntSetting;
import org.jspecify.annotations.Nullable;

/**
 * Weather Changer: the sky as the player likes it, on their screen only. Clear skies in a storm,
 * snow anywhere, and the time of day held where they put it. The server's weather and clock go on
 * as before, and come back when the module's off.
 */
public final class WeatherChanger extends Module {

    /** Which weather shows. */
    public enum Sky {
        WORLD("World's own", null),
        CLEAR("Clear", Weather.CLEAR),
        RAIN("Rain", Weather.RAIN),
        THUNDER("Thunder", Weather.THUNDER),
        SNOW("Snow", Weather.SNOW);

        private final String label;
        private final @Nullable Weather weather;

        Sky(String label, @Nullable Weather weather) {
            this.label = label;
            this.weather = weather;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** Ticks in a day; the day starts at 6:00. */
    static final int DAY = 24000;

    final ChoiceSetting<Sky> sky = settings()
            .choice("weather", "Weather", Sky.values(), Sky.CLEAR, Sky::label)
            .describedAs("Changes the weather on your screen only.");
    final BooleanSetting holdTime = settings()
            .bool("hold-time", "Hold the time", false)
            .describedAs("Freezes the time of day on your screen. The world's time keeps going.");
    final IntSetting hour = settings()
            .integer("hour", "Time", 0, 23, 12)
            .formattedAs(WeatherChanger::clock)
            .describedAs("The time of day you see.")
            .visibleWhen(holdTime::get);

    /** Weather Changer, off until the player turns it on. */
    public WeatherChanger() {
        super(
                "weatherchanger",
                "Weather Changer",
                "Changes the weather and time of day you see, on your screen only.",
                Category.VISUAL);
        on(Events.TICK, this::tick);
    }

    private void tick() {
        system().game().overrideSky(sky.get().weather, holdTime.get() ? ticks(hour.get()) : -1);
    }

    @Override
    protected void onEnable() {
        tick();
    }

    @Override
    protected void onDisable() {
        system().game().overrideSky(null, -1);
    }

    /** The game's ticks for {@code hour} o'clock: its day starts at 6:00. */
    static int ticks(int hour) {
        return Math.floorMod(hour - 6, 24) * 1000;
    }

    /** "06:00". */
    static String clock(int hour) {
        return (hour < 10 ? "0" : "") + hour + ":00";
    }
}
