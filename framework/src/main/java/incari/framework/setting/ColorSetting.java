package incari.framework.setting;

/**
 * A color as {@code 0xAARRGGBB}. Without alpha ({@link #opaque}), the alpha byte is always
 * {@code FF}.
 */
public final class ColorSetting extends Setting<ColorSetting> {

    private final int defaultValue;
    private final boolean alpha;
    private int value;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param defaultValue {@code 0xAARRGGBB}
     * @param alpha whether the player may make it see-through
     */
    public ColorSetting(String id, String name, int defaultValue, boolean alpha) {
        super(id, name);
        this.alpha = alpha;
        this.defaultValue = alpha ? defaultValue : defaultValue | 0xFF000000;
        this.value = this.defaultValue;
    }

    /** An opaque color setting. */
    public static ColorSetting opaque(String id, String name, int rgb) {
        return new ColorSetting(id, name, rgb, false);
    }

    /** The color, {@code 0xAARRGGBB}. */
    public int get() {
        return value;
    }

    /** Sets the color; an opaque setting keeps its alpha at {@code FF}. */
    public void set(int argb) {
        int kept = alpha ? argb : argb | 0xFF000000;
        if (value != kept) {
            value = kept;
            changed();
        }
    }

    /** Whether the alpha byte is the player's to set. */
    public boolean hasAlpha() {
        return alpha;
    }

    /** The color before the player changes it. */
    public int defaultValue() {
        return defaultValue;
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
    protected ColorSetting self() {
        return this;
    }
}
