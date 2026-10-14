package incari.framework.setting;

import java.util.function.DoubleFunction;

/**
 * A decimal number between a minimum and a maximum, in steps (a scale from 0.5 to 2 in steps of
 * 0.1, say). Set values are clamped and rounded to the nearest step from the minimum.
 */
public final class FloatSetting extends Setting<FloatSetting> {

    private final float min;
    private final float max;
    private final float step;
    private final float defaultValue;
    private float value;
    private DoubleFunction<String> format;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param min the smallest value
     * @param max the largest value
     * @param step the spacing of the values in between; positive
     * @param defaultValue the value before the player changes it, in range
     */
    public FloatSetting(String id, String name, float min, float max, float step, float defaultValue) {
        super(id, name);
        if (!(min <= max) || !(step > 0) || !(defaultValue >= min && defaultValue <= max)) {
            throw new IllegalArgumentException(
                    id + ": default " + defaultValue + " outside " + min + ".." + max + " or step " + step);
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        int decimals = decimals(step);
        this.format = v -> trim(v, decimals);
    }

    /** The value. */
    public float get() {
        return value;
    }

    /** Sets the value, clamped into range and rounded to a step. NaN is ignored. */
    public void set(float value) {
        if (Float.isNaN(value)) {
            return;
        }
        float stepped = min + Math.round((value - min) / step) * step;
        float clamped = Math.max(min, Math.min(max, stepped));
        if (this.value != clamped) {
            this.value = clamped;
            changed();
        }
    }

    /** The smallest value. */
    public float min() {
        return min;
    }

    /** The largest value. */
    public float max() {
        return max;
    }

    /** The spacing of the values. */
    public float step() {
        return step;
    }

    /** The value before the player changes it. */
    public float defaultValue() {
        return defaultValue;
    }

    /** How the settings screen writes a value ("1.5x", say); as many decimals as the step by default. */
    public FloatSetting formattedAs(DoubleFunction<String> format) {
        this.format = format;
        return this;
    }

    /** {@code value} as the settings screen writes it. */
    public String format(float value) {
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
    protected FloatSetting self() {
        return this;
    }

    /** How many decimals a step needs: 0.25 needs 2, 0.1 needs 1, 5 needs none. */
    static int decimals(float step) {
        for (int decimals = 0; decimals < 4; decimals++) {
            double scaled = step * Math.pow(10, decimals);
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-4) {
                return decimals;
            }
        }
        return 4;
    }

    /** Writes {@code value} with {@code decimals} places (no locale: the screen's digits are ASCII). */
    static String trim(double value, int decimals) {
        long scale = (long) Math.pow(10, decimals);
        long rounded = Math.round(Math.abs(value) * scale);
        String sign = value < 0 && rounded != 0 ? "-" : "";
        if (decimals == 0) {
            return sign + rounded;
        }
        StringBuilder fraction = new StringBuilder(Long.toString(rounded % scale));
        while (fraction.length() < decimals) {
            fraction.insert(0, '0');
        }
        return sign + rounded / scale + "." + fraction;
    }
}
