package incari.client.ui;

import incari.framework.setting.ActionSetting;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.FloatSetting;
import incari.framework.setting.GridSetting;
import incari.framework.setting.IntSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Setting;
import incari.framework.setting.SettingVisitor;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The control for each kind of setting, made from the setting alone: this is what makes a module's
 * settings screen need no code of its own. A switch for on/off, a slider for numbers, a cycling
 * button for choices, a hex field with a swatch for colors, and a button that waits for a key for
 * keybinds. Built from {@link Ui}'s widgets, so they restyle with it.
 */
public final class SettingControls implements SettingVisitor<SettingControls.Control> {

    /** A setting's control, and how to bring it up to date when the value changes elsewhere. */
    public record Control(AbstractWidget widget, Runnable refresh) {}

    /** The screen's side of a keybind control: which bind is waiting for a key. */
    public interface KeyCapture {

        /** Starts waiting for a key for {@code setting}: the next key or button pressed binds it. */
        void capture(KeySetting setting);

        /** The bind waiting for a key, or null. */
        @Nullable
        KeySetting capturing();
    }

    /** The game's names for keys. */
    public interface KeyNames {

        /** What the game calls {@code key} ("Left Control", "Mouse Button 4"). */
        Component name(Key key);

        /** What else {@code setting}'s key is bound to, by name; empty if nothing. */
        List<String> conflicts(KeySetting setting);
    }

    private final Font font;
    private final KeyCapture keys;
    private final KeyNames names;

    /** Controls for one screen. */
    public SettingControls(Font font, KeyCapture keys, KeyNames names) {
        this.font = font;
        this.keys = keys;
        this.names = names;
    }

    /** {@code setting}'s control. */
    public Control control(Setting<?> setting) {
        return setting.accept(this);
    }

    @Override
    public Control visit(BooleanSetting setting) {
        CycleButton<Boolean> button =
                Ui.toggle(Component.literal(setting.name()), setting.get(), Theme.CONTROL_WIDTH, setting::set);
        return new Control(button, () -> button.setValue(setting.get()));
    }

    @Override
    public Control visit(IntSetting setting) {
        Ui.Slider slider = Ui.slider(
                setting.min(),
                setting.max(),
                setting.get(),
                Theme.CONTROL_WIDTH,
                value -> Component.literal(setting.format((int) Math.round(value))),
                value -> setting.set((int) Math.round(value)));
        return new Control(slider, () -> slider.show(setting.get()));
    }

    @Override
    public Control visit(FloatSetting setting) {
        Ui.Slider slider = Ui.slider(
                setting.min(),
                setting.max(),
                setting.get(),
                Theme.CONTROL_WIDTH,
                // The label shows the value it'll snap to, not where the handle is between steps.
                value -> Component.literal(setting.format(snap(setting, value))),
                value -> setting.set((float) value));
        return new Control(slider, () -> slider.show(setting.get()));
    }

    @Override
    public Control visit(ChoiceSetting<?> setting) {
        return choice(setting);
    }

    private <E extends Enum<E>> Control choice(ChoiceSetting<E> setting) {
        CycleButton<E> button = Ui.choice(
                Component.literal(setting.name()),
                setting.values(),
                setting.get(),
                choice -> Component.literal(setting.label(choice)),
                Theme.CONTROL_WIDTH,
                setting::set);
        return new Control(button, () -> button.setValue(setting.get()));
    }

    @Override
    public Control visit(ColorSetting setting) {
        Ui.ColorField field = Ui.colorField(
                font,
                Component.literal(setting.name()),
                setting.get(),
                setting.hasAlpha(),
                Theme.CONTROL_WIDTH,
                setting::set);
        return new Control(field, () -> field.show(setting.get()));
    }

    @Override
    public Control visit(KeySetting setting) {
        Button button = Ui.button(Component.empty(), Theme.CONTROL_WIDTH, () -> keys.capture(setting));
        Runnable refresh = () -> {
            Component name = names.name(setting.get());
            List<String> conflicts = names.conflicts(setting);
            if (setting.equals(keys.capturing())) {
                button.setMessage(Component.literal("> ")
                        .append(name.copy().withStyle(ChatFormatting.UNDERLINE))
                        .append(" <")
                        .withColor(Theme.ACCENT));
            } else if (!conflicts.isEmpty()) {
                button.setMessage(
                        Component.literal("[ ").append(name).append(" ]").withColor(Theme.WARNING));
            } else {
                button.setMessage(name);
            }
            button.setTooltip(
                    conflicts.isEmpty()
                            ? null
                            : Tooltip.create(Component.literal("Also bound to " + String.join(", ", conflicts))));
        };
        refresh.run();
        return new Control(button, refresh);
    }

    @Override
    public Control visit(ActionSetting setting) {
        // As wide as another row's control and its Reset together: a button has no value to reset.
        Button button = Ui.button(
                Component.literal(setting.label()),
                Theme.CONTROL_WIDTH + Theme.GAP + Theme.SMALL_BUTTON_WIDTH,
                setting::run);
        return new Control(button, () -> {});
    }

    @Override
    public Control visit(GridSetting setting) {
        Button button = Ui.button(Component.literal("Edit..."), Theme.CONTROL_WIDTH, () -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new GridScreen(minecraft.gui.screen(), setting));
        });
        return new Control(button, () -> {});
    }

    private static float snap(FloatSetting setting, double value) {
        float stepped = setting.min() + Math.round((value - setting.min()) / setting.step()) * setting.step();
        return Math.max(setting.min(), Math.min(setting.max(), stepped));
    }
}
