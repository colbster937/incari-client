package incari.framework.setting;

import java.util.ArrayList;
import java.util.List;

/**
 * A keybind: which {@link Key} does something. The module system reads the key once a tick through
 * the game's own key handling, so a bind fires only with no screen open, as the game's do, and a
 * press shorter than a tick still counts.
 *
 * <p>A module's binds other than its toggle key fire only while it's enabled.
 */
public final class KeySetting extends Setting<KeySetting> {

    private final Key defaultValue;
    private Key value;
    private boolean down;
    private final List<Runnable> pressHandlers = new ArrayList<>(1);

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param defaultValue the key before the player changes it; {@link Key#NONE} for none
     */
    public KeySetting(String id, String name, Key defaultValue) {
        super(id, name);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /** The key. */
    public Key get() {
        return value;
    }

    /** Binds another key, or {@link Key#NONE}. */
    public void set(Key key) {
        if (!value.equals(key)) {
            value = key;
            changed();
        }
    }

    /** The key before the player changes it. */
    public Key defaultValue() {
        return defaultValue;
    }

    /** Runs {@code handler} on every press. */
    public KeySetting onPress(Runnable handler) {
        pressHandlers.add(handler);
        return this;
    }

    /** Whether the key is held now (as of this tick), with no screen open. */
    public boolean isDown() {
        return down;
    }

    /** The module system's: the key's state this tick. */
    public void update(boolean down) {
        this.down = down;
    }

    /** The module system's: runs the press handlers once. */
    public void press() {
        for (int i = 0; i < pressHandlers.size(); i++) {
            pressHandlers.get(i).run();
        }
    }

    @Override
    public boolean isDefault() {
        return value.equals(defaultValue);
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
    protected KeySetting self() {
        return this;
    }
}
