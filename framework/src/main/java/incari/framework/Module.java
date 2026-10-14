package incari.framework;

import incari.framework.event.Channel;
import incari.framework.event.EventBus;
import incari.framework.event.Events;
import incari.framework.game.Game;
import incari.framework.game.GameOption;
import incari.framework.hud.HudElement;
import incari.framework.setting.Ids;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.jspecify.annotations.Nullable;

/**
 * A client feature the player turns on and off: toggle sprint, keystrokes, zoom. A subclass
 * declares everything in its constructor (its settings as fields, its HUD elements, the events it
 * listens to) and the framework does the rest: the settings screen is generated from the settings,
 * the profile saves them, and the listeners are subscribed only while the module is enabled, so a
 * disabled module costs nothing.
 *
 * <p>A module sees the game only through the framework's events and canvases: no game types (ADR
 * 0046). Everything runs on the game thread.
 *
 * <pre>{@code
 * final class Fullbright extends Module {
 *     private final FloatSetting level = settings().decimal("level", "Brightness", 0, 1, 0.1F, 1);
 *
 *     Fullbright() {
 *         super("fullbright", "Fullbright", "Lights everything up.", Category.VISUAL);
 *         on(Events.TICK, this::tick);
 *     }
 * }
 * }</pre>
 */
public abstract class Module {

    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final Settings settings = new Settings();
    private final KeySetting toggleKey;
    private final List<HudElement> hud = new ArrayList<>();
    private final List<HudElement> hudView = Collections.unmodifiableList(hud);
    /** Channel, listener, channel, listener... */
    private final List<Object> subscriptions = new ArrayList<>();

    private final List<OptionHold> holds = new ArrayList<>(0);

    /** The resource pack this module is the switch for, or null ({@link #followPack}). */
    private @Nullable String pack;

    private final List<MenuButton> menuButtons = new ArrayList<>(0);
    private final List<MenuButton> menuButtonsView = Collections.unmodifiableList(menuButtons);

    private boolean enabled;
    private boolean alwaysOn;
    /** True while a throw turns the module off: an always-on module is turned off then too. */
    private boolean failing;

    private @Nullable ModuleSystem system;

    /**
     * @param id the config key and the module's name in logs: lowercase, stable forever
     * @param name what the module menu calls it
     * @param description one sentence: what it does
     * @param category where the module menu lists it
     */
    protected Module(String id, String name, String description, Category category) {
        this.id = Ids.require(id);
        this.name = name;
        this.description = description;
        this.category = category;
        this.toggleKey = settings.key("toggle", "Toggle key", Key.NONE).describedAs("Turns " + name + " on and off.");
    }

    /** The config key. */
    public final String id() {
        return id;
    }

    /** What the module menu calls it. */
    public final String name() {
        return name;
    }

    /** One sentence: what it does. */
    public final String description() {
        return description;
    }

    /** Where the module menu lists it. */
    public final Category category() {
        return category;
    }

    /** Its settings, the toggle key first. Declare a module's own in field initializers. */
    public final Settings settings() {
        return settings;
    }

    /** The key that turns it on and off; unbound by default. It fires while the module is off too. */
    public final KeySetting toggleKey() {
        return toggleKey;
    }

    /** Its HUD elements, in the order they were added. */
    public final List<HudElement> hudElements() {
        return hudView;
    }

    /** Whether it's on. */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether it's part of the client rather than a choice ({@link #alwaysOn()}).
     */
    public final boolean isAlwaysOn() {
        return alwaysOn;
    }

    /**
     * Turns it on or off: its listeners are subscribed or unsubscribed, then its hook runs. An
     * {@linkplain #isAlwaysOn() always-on} module isn't turned off.
     */
    public final void setEnabled(boolean on) {
        if (on == enabled || (alwaysOn && !on && !failing)) {
            return;
        }
        ModuleSystem owner = system();
        EventBus bus = owner.events();
        enabled = on;
        for (int i = 0; i < subscriptions.size(); i += 2) {
            if (on) {
                subscribe(bus, (Channel<?>) subscriptions.get(i), subscriptions.get(i + 1));
            } else {
                unsubscribe(bus, (Channel<?>) subscriptions.get(i), subscriptions.get(i + 1));
            }
        }
        owner.enabledChanged(this);
        Game game = owner.game();
        try {
            if (on) {
                for (int i = 0; i < holds.size(); i++) {
                    holds.get(i).take(game);
                }
                onEnable();
            } else {
                onDisable();
                for (int i = 0; i < holds.size(); i++) {
                    holds.get(i).release(game);
                }
            }
            if (pack != null) {
                owner.selectPack(this, pack, on);
            }
        } catch (RuntimeException e) {
            // Reported and, if it was turning on, turned off again.
            owner.failed(this, on ? "turning on" : "turning off", e);
        }
    }

    /** Turns it on if it's off, off if it's on. */
    public final void toggle() {
        setEnabled(!enabled);
    }

    /** Runs after it's turned on (also when a profile loads with it on). */
    protected void onEnable() {}

    /** Runs after it's turned off (also when a profile loads with it off, or the game leaves). */
    protected void onDisable() {}

    /**
     * Listens to {@code channel} while the module is enabled. Call from the constructor; the
     * listener should be a field or a method reference made once.
     */
    protected final <L> void on(Channel<L> channel, L listener) {
        if (system != null) {
            throw new IllegalStateException(id + ": subscribe in the constructor");
        }
        subscriptions.add(channel);
        subscriptions.add(listener);
    }

    /**
     * Holds a game option at {@code value} while the module is on, following it as the settings
     * change (Clear Chat holds the chat's background opacity at 0); the player's value comes back
     * when it turns off. Moving the option's slider turns the module off and keeps the player's
     * value. Call from the constructor.
     */
    protected final void holdOption(GameOption option, DoubleSupplier value) {
        for (OptionHold hold : holds) {
            if (hold.option() == option) {
                throw new IllegalArgumentException(id + ": holds " + option + " twice");
            }
        }
        OptionHold hold = new OptionHold(this, option, value);
        holds.add(hold);
        on(Events.TICK, hold);
    }

    /**
     * Makes the module the switch for the game's resource pack {@code packId} (Old Textures for
     * {@code programmer_art}): the module and the pack turn on and off together, from either side.
     * At start the module follows the pack, whatever the profile says. Call from the constructor.
     */
    protected final void followPack(String packId) {
        if (system != null) {
            throw new IllegalStateException(id + ": follow a pack in the constructor");
        }
        if (pack != null) {
            throw new IllegalArgumentException(id + ": follows " + pack + " already");
        }
        pack = packId;
    }

    /**
     * Makes the module part of the client, not a choice (the Theme Editor): always on, with no
     * toggle key, and left out of the module menu's cards and presets. Its settings screen opens
     * from elsewhere. Call from the constructor.
     */
    protected final void alwaysOn() {
        if (system != null) {
            throw new IllegalStateException(id + ": be always on from the constructor");
        }
        alwaysOn = true;
        toggleKey.hidden();
    }

    /** The resource pack it's the switch for, or null. */
    final @Nullable String pack() {
        return pack;
    }

    /**
     * Puts a button on the title screen and the pause menu while the module is on. Call from the
     * constructor.
     */
    protected final MenuButton menuButton(String buttonId, String label, String icon, Runnable action) {
        if (system != null) {
            throw new IllegalStateException(id + ": add menu buttons in the constructor");
        }
        MenuButton button = new MenuButton(Ids.require(buttonId), label, icon, action);
        menuButtons.add(button);
        return button;
    }

    /** Its menu buttons, in the order they were added. */
    public final List<MenuButton> menuButtons() {
        return menuButtonsView;
    }

    /** Adds a HUD element, drawn while the module is enabled. Call from the constructor. */
    protected final <E extends HudElement> E hud(E element) {
        if (system != null) {
            throw new IllegalStateException(id + ": add HUD elements in the constructor");
        }
        for (HudElement other : hud) {
            if (other.id().equals(element.id())) {
                throw new IllegalArgumentException(id + ": two HUD elements with the id " + element.id());
            }
        }
        hud.add(element);
        return element;
    }

    /** The module system it belongs to. */
    protected final ModuleSystem system() {
        ModuleSystem owner = system;
        if (owner == null) {
            throw new IllegalStateException(id + " isn't in a module system yet");
        }
        return owner;
    }

    final void register(ModuleSystem owner) {
        if (system != null) {
            throw new IllegalStateException(id + " is already in a module system");
        }
        system = owner;
    }

    /** Turned off because it threw: like {@link #setEnabled(boolean) setEnabled(false)}, quietly. */
    final void disableAfterFailure() {
        if (enabled) {
            failing = true;
            try {
                setEnabled(false);
            } finally {
                failing = false;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <L> void subscribe(EventBus bus, Channel<L> channel, Object listener) {
        // The module is the owner a listener's failure is reported against.
        bus.add(channel, this, (L) listener);
    }

    @SuppressWarnings("unchecked")
    private static <L> void unsubscribe(EventBus bus, Channel<L> channel, Object listener) {
        bus.remove(channel, (L) listener);
    }

    @Override
    public String toString() {
        return id;
    }
}
