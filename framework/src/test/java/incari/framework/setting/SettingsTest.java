package incari.framework.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsTest {

    private enum Size {
        SMALL,
        LARGE
    }

    @Test
    void intsAreClampedIntoRange() {
        IntSetting setting = new IntSetting("range", "Range", 5, 32, 16);
        setting.set(100);
        assertThat(setting.get()).isEqualTo(32);
        setting.set(-3);
        assertThat(setting.get()).isEqualTo(5);
        assertThat(setting.isDefault()).isFalse();
        setting.reset();
        assertThat(setting.get()).isEqualTo(16);
        assertThat(setting.isDefault()).isTrue();
    }

    @Test
    void floatsSnapToTheirSteps() {
        FloatSetting setting = new FloatSetting("scale", "Scale", 0.5F, 3.0F, 0.25F, 1.0F);
        setting.set(1.3F);
        assertThat(setting.get()).isEqualTo(1.25F);
        setting.set(9);
        assertThat(setting.get()).isEqualTo(3.0F);
        setting.set(Float.NaN);
        assertThat(setting.get()).isEqualTo(3.0F);
        assertThat(setting.format(1.25F)).isEqualTo("1.25");
    }

    @Test
    void floatFormatsShowTheStepsDecimals() {
        assertThat(FloatSetting.decimals(0.25F)).isEqualTo(2);
        assertThat(FloatSetting.decimals(0.1F)).isEqualTo(1);
        assertThat(FloatSetting.decimals(5)).isZero();
        assertThat(FloatSetting.trim(1.5, 2)).isEqualTo("1.50");
        assertThat(FloatSetting.trim(-0.05, 1)).isEqualTo("-0.1");
        assertThat(FloatSetting.trim(-0.01, 1)).isEqualTo("0.0");
        assertThat(FloatSetting.trim(12, 0)).isEqualTo("12");
    }

    @Test
    void choicesCycleAndAreKeyedByLowercaseName() {
        ChoiceSetting<Size> setting = new ChoiceSetting<>(
                "size", "Size", Size.values(), Size.SMALL, size -> size.name().toLowerCase(java.util.Locale.ROOT));
        setting.next();
        assertThat(setting.get()).isEqualTo(Size.LARGE);
        setting.next();
        assertThat(setting.get()).isEqualTo(Size.SMALL);
        assertThat(setting.fromKey("large")).isEqualTo(Size.LARGE);
        assertThat(setting.fromKey("LARGE")).isNull();
        assertThat(setting.label(Size.LARGE)).isEqualTo("large");
    }

    @Test
    void opaqueColorsKeepTheirAlpha() {
        ColorSetting setting = ColorSetting.opaque("color", "Color", 0x123456);
        assertThat(setting.get()).isEqualTo(0xFF123456);
        setting.set(0x00ABCDEF);
        assertThat(setting.get()).isEqualTo(0xFFABCDEF);
        ColorSetting translucent = new ColorSetting("bg", "Background", 0x80000000, true);
        assertThat(translucent.get()).isEqualTo(0x80000000);
    }

    @Test
    void keysRoundTripThroughTheirText() {
        assertThat(Key.keyboard(224).encode()).isEqualTo("keyboard:224");
        assertThat(Key.decode("keyboard:224")).isEqualTo(Key.keyboard(224));
        assertThat(Key.decode("mouse:3")).isEqualTo(Key.mouse(3));
        assertThat(Key.NONE.encode()).isEqualTo("none");
        assertThat(Key.decode("none")).isEqualTo(Key.NONE);
        assertThat(Key.decode("keyboard:0")).isEqualTo(Key.NONE);
        assertThat(Key.decode("joystick:1")).isNull();
        assertThat(Key.decode("keyboard:-1")).isNull();
        assertThat(Key.decode("keyboard")).isNull();
        assertThat(Key.NONE.isBound()).isFalse();
    }

    @Test
    void listenersHearChangesButNotSameValueSets() {
        List<String> heard = new ArrayList<>();
        Settings settings = new Settings();
        settings.attach(() -> heard.add("owner"));
        BooleanSetting on = settings.bool("on", "On", false);
        on.onChange(() -> heard.add("setting"));

        on.set(false);
        on.set(true);

        assertThat(heard).containsExactly("setting", "owner");
    }

    @Test
    void visibilityFollowsItsConditionAndHiddenWins() {
        Settings settings = new Settings();
        BooleanSetting show = settings.bool("show", "Show", false);
        ColorSetting color = settings.color("color", "Color", 0xFFFFFF).visibleWhen(show::get);
        IntSetting offset = settings.integer("x", "X", 0, 10, 0).hidden();

        assertThat(color.isVisible()).isFalse();
        show.set(true);
        assertThat(color.isVisible()).isTrue();
        assertThat(offset.isVisible()).isFalse();
        assertThat(settings.anyShown()).isTrue();
    }

    @Test
    void idsAreChecked() {
        assertThat(Ids.valid("show-status")).isTrue();
        assertThat(Ids.valid("Show")).isFalse();
        assertThat(Ids.valid("1st")).isFalse();
        assertThat(Ids.valid("")).isFalse();
        assertThatThrownBy(() -> new BooleanSetting("bad id", "Bad", false))
                .isInstanceOf(IllegalArgumentException.class);
        Settings settings = new Settings();
        settings.bool("same", "Same", false);
        assertThatThrownBy(() -> settings.bool("same", "Again", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsMustBeInRange() {
        assertThatThrownBy(() -> new IntSetting("n", "N", 0, 10, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FloatSetting("f", "F", 0, 1, 0, 0.5F))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
