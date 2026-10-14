package incari.framework.setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A module's or HUD element's settings, in the order the settings screen lists them. Declared as
 * fields, in the constructor; the list is fixed once the module system has the module.
 */
public final class Settings implements Iterable<Setting<?>> {

    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> view = Collections.unmodifiableList(settings);
    private Runnable owner = () -> {};

    /** Adds {@code setting} at the end of the list. */
    public <S extends Setting<S>> S add(S setting) {
        if (get(setting.id()) != null) {
            throw new IllegalArgumentException("Two settings with the id " + setting.id());
        }
        settings.add(setting);
        setting.attach(this::changed);
        return setting;
    }

    /** Adds an on/off setting. */
    public BooleanSetting bool(String id, String name, boolean defaultValue) {
        return add(new BooleanSetting(id, name, defaultValue));
    }

    /** Adds a whole-number setting. */
    public IntSetting integer(String id, String name, int min, int max, int defaultValue) {
        return add(new IntSetting(id, name, min, max, defaultValue));
    }

    /** Adds a decimal setting. */
    public FloatSetting decimal(String id, String name, float min, float max, float step, float defaultValue) {
        return add(new FloatSetting(id, name, min, max, step, defaultValue));
    }

    /** Adds a choice among an enum's constants. */
    public <E extends Enum<E>> ChoiceSetting<E> choice(
            String id, String name, E[] values, E defaultValue, Function<E, String> label) {
        return add(new ChoiceSetting<>(id, name, values, defaultValue, label));
    }

    /** Adds an opaque color, {@code 0xRRGGBB}. */
    public ColorSetting color(String id, String name, int rgb) {
        return add(ColorSetting.opaque(id, name, rgb));
    }

    /** Adds a button that runs {@code action}, saying {@code label}. */
    public ActionSetting action(String id, String name, String label, Runnable action) {
        return add(new ActionSetting(id, name, label, action));
    }

    /** Adds a square of {@code size} cells a side, as {@link GridSetting} describes. */
    public GridSetting grid(String id, String name, int size, long[] defaultRows) {
        return add(new GridSetting(id, name, size, defaultRows));
    }

    /** Adds a keybind. */
    public KeySetting key(String id, String name, Key defaultKey) {
        return add(new KeySetting(id, name, defaultKey));
    }

    /** The setting with this id, or null. */
    public @Nullable Setting<?> get(String id) {
        for (Setting<?> setting : settings) {
            if (setting.id().equals(id)) {
                return setting;
            }
        }
        return null;
    }

    /** Every setting, in order. Unmodifiable. */
    public List<Setting<?>> all() {
        return view;
    }

    /** Whether any setting shows in the generated settings screen. */
    public boolean anyShown() {
        for (Setting<?> setting : settings) {
            if (!setting.isHidden()) {
                return true;
            }
        }
        return false;
    }

    /** Puts every setting's default back. */
    public void resetAll() {
        for (Setting<?> setting : settings) {
            setting.reset();
        }
    }

    @Override
    public Iterator<Setting<?>> iterator() {
        return view.iterator();
    }

    /** The module system's: where changes are reported. */
    public void attach(Runnable owner) {
        this.owner = owner;
    }

    private void changed() {
        owner.run();
    }
}
