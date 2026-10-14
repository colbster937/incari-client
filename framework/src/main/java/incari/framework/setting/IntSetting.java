package incari.framework.setting;

import java.util.function.IntFunction;

/** A whole number between a minimum and a maximum, both included. Set values are clamped. */
public final class IntSetting extends Setting<IntSetting> {

    private final int min;
    private final int max;
    private final int defaultValue;
    private int value;
    private IntFunction<String> format = Integer::toString;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param min the smallest value
     * @param max the largest value
     * @param defaultValue the value before the player changes it, in range
     */
    public IntSetting(String id, String name, int min, int max, int defaultValue) {
        super(id, name);
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException(id + ": default " + defaultValue + " outside " + min + ".." + max);
        }
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /** The value. */
    public int get() {
        return value;
    }

    /** Sets the value, clamped into range. */
    public void set(int value) {
        int clamped = Math.max(min, Math.min(max, value));
        if (this.value != clamped) {
            this.value = clamped;
            changed();
        }
    }

    /** The smallest value. */
    public int min() {
        return min;
    }

    /** The largest value. */
    public int max() {
        return max;
    }

    /** The value before the player changes it. */
    public int defaultValue() {
        return defaultValue;
    }

    /** How the settings screen writes a value ("12 blocks", say); the bare number by default. */
    public IntSetting formattedAs(IntFunction<String> format) {
        this.format = format;
        return this;
    }

    /** {@code value} as the settings screen writes it. */
    public String format(int value) {
        return format.apply(value);
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
    protected IntSetting self() {
        return this;
    }
}
