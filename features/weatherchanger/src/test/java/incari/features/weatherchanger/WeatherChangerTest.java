package incari.features.weatherchanger;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.Weather;
import incari.framework.setting.BooleanSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class WeatherChangerTest {

    private final WeatherChanger module = new WeatherChanger();
    private final TestGame game = new TestGame(module);

    @Test
    void showsClearSkiesWhileOnAndTheWorldsAfter() {
        module.setEnabled(true);
        assertThat(game.weather).isEqualTo(Weather.CLEAR);
        assertThat(game.timeOfDay).isEqualTo(-1);

        module.sky.set(WeatherChanger.Sky.SNOW);
        game.tick();
        assertThat(game.weather).isEqualTo(Weather.SNOW);

        module.setEnabled(false);
        assertThat(game.weather).isNull();
    }

    @Test
    void holdsTheTimeOfDay() {
        module.setEnabled(true);
        ((BooleanSetting) module.settings().get("hold-time")).set(true);
        game.tick();
        assertThat(game.timeOfDay).isEqualTo(6000);
    }

    @Test
    void theGamesDayStartsAtSix() {
        assertThat(WeatherChanger.ticks(6)).isZero();
        assertThat(WeatherChanger.ticks(0)).isEqualTo(18000);
        assertThat(WeatherChanger.clock(7)).isEqualTo("07:00");
    }
}
