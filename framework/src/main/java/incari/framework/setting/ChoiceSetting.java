package incari.framework.setting;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One of an enum's constants. Stored by the constant's name in lowercase, so reordering the enum
 * keeps what players chose; renaming a constant doesn't.
 *
 * @param <E> the enum
 */
public final class ChoiceSetting<E extends Enum<E>> extends Setting<ChoiceSetting<E>> {

    private final List<E> values;
    private final E defaultValue;
    private final Function<E, String> label;
    private E value;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param values every choice, in the order the settings screen cycles through them
     * @param defaultValue the value before the player changes it, one of {@code values}
     * @param label what the settings screen calls each choice
     */
    public ChoiceSetting(String id, String name, E[] values, E defaultValue, Function<E, String> label) {
        super(id, name);
        if (values.length == 0) {
            throw new IllegalArgumentException(id + ": no choices");
        }
        this.values = List.of(values);
        if (!this.values.contains(defaultValue)) {
            throw new IllegalArgumentException(id + ": default " + defaultValue + " isn't a choice");
        }
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.label = label;
    }

    /** The value. */
    public E get() {
        return value;
    }

    /** Sets the value; one that isn't a choice is ignored. */
    public void set(E value) {
        if (this.value != value && values.contains(value)) {
            this.value = value;
            changed();
        }
    }

    /** Moves to the next choice, from the last back to the first. */
    public void next() {
        set(values.get((values.indexOf(value) + 1) % values.size()));
    }

    /** Every choice, in order. */
    public List<E> values() {
        return values;
    }

    /** The value before the player changes it. */
    public E defaultValue() {
        return defaultValue;
    }

    /** What the settings screen calls {@code choice}. */
    public String label(E choice) {
        return label.apply(choice);
    }

    /** What the settings screen calls the current value. */
    public String label() {
        return label.apply(value);
    }

    /** The config's name for the current value. */
    public String key() {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The choice the config's {@code key} names, or null if none does. */
    public @Nullable E fromKey(String key) {
        for (E choice : values) {
            if (choice.name().toLowerCase(Locale.ROOT).equals(key)) {
                return choice;
            }
        }
        return null;
    }

    @Override
    public boolean isDefault() {
        return value == defaultValue;
    }

    @Override
    public void reset() {
        set(defaultValue);
    }

    @Override
    public <R> R accept(SettingVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    protected ChoiceSetting<E> self() {
        return this;
    }
}
