package incari.framework.setting;

/** An on/off setting. Read with {@link #get()}: no boxing, fine on hot paths. */
public final class BooleanSetting extends Setting<BooleanSetting> {

    private final boolean defaultValue;
    private boolean value;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param defaultValue the value before the player changes it
     */
    public BooleanSetting(String id, String name, boolean defaultValue) {
        super(id, name);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /** The value. */
    public boolean get() {
        return value;
    }

    /** Sets the value; telling listeners only if it changed. */
    public void set(boolean value) {
        if (this.value != value) {
            this.value = value;
            changed();
        }
    }

    /** Flips the value. */
    public void toggle() {
        set(!value);
    }

    /** The value before the player changes it. */
    public boolean defaultValue() {
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
    protected BooleanSetting self() {
        return this;
    }
}
