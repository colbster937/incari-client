package incari.framework.setting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One typed value a module or HUD element offers the player: a schema entry the settings screen is
 * generated from and the profile's config stores. A module never draws its own settings; the UI
 * reaches each kind through {@link SettingVisitor}, so restyling the UI changes no module.
 *
 * <p>Game thread only. A setting's id is part of the saved config: renaming one loses what players
 * set.
 *
 * @param <S> the setting's own type, for the fluent configuration methods
 */
public abstract class Setting<S extends Setting<S>> {

    private final String id;
    private final String name;
    private String description = "";
    private @Nullable BooleanSupplier visible;
    private boolean hidden;
    private final List<Runnable> listeners = new ArrayList<>(1);
    private @Nullable Runnable container;

    /**
     * @param id the config key: lowercase letters, digits, {@code _} and {@code -}
     * @param name what the settings screen calls it
     */
    protected Setting(String id, String name) {
        this.id = Ids.require(id);
        this.name = name;
    }

    /** The config key, unique within its module or HUD element. */
    public final String id() {
        return id;
    }

    /** The name the settings screen shows. */
    public final String name() {
        return name;
    }

    /** One sentence the settings screen shows as the setting's tooltip; empty for none. */
    public final String description() {
        return description;
    }

    /** Sets the tooltip sentence. */
    public final S describedAs(String sentence) {
        this.description = sentence;
        return self();
    }

    /**
     * Shows the setting only while {@code condition} holds (a color only while its text is shown,
     * say). The settings screen asks again whenever any setting changes.
     */
    public final S visibleWhen(BooleanSupplier condition) {
        this.visible = condition;
        return self();
    }

    /**
     * Keeps the setting out of the generated settings screen: it's stored and restored, but set by
     * something else (a HUD element's position, which the HUD editor sets).
     */
    public final S hidden() {
        this.hidden = true;
        return self();
    }

    /** Whether the generated settings screen shows it now. */
    public final boolean isVisible() {
        BooleanSupplier condition = visible;
        return !hidden && (condition == null || condition.getAsBoolean());
    }

    /** Whether it's kept out of the generated settings screen for good ({@link #hidden()}). */
    public final boolean isHidden() {
        return hidden;
    }

    /** Runs {@code listener} after every change to the value, from the screen or a profile load. */
    public final void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Whether the value is the default. */
    public abstract boolean isDefault();

    /** Puts the default back. */
    public abstract void reset();

    /** Calls the visitor's method for this kind of setting. */
    public abstract <R> R accept(SettingVisitor<R> visitor);

    /** Every subclass returns itself, for the fluent methods. */
    protected abstract S self();

    /** Subclasses call this after the value changed. */
    protected final void changed() {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).run();
        }
        Runnable owner = container;
        if (owner != null) {
            owner.run();
        }
    }

    /** {@link Settings} tells its container (the module system) about every change through this. */
    final void attach(Runnable owner) {
        this.container = owner;
    }
}
