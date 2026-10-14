package incari.features.togglesprint;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.event.MovementInput;
import incari.framework.game.PlayerView;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import org.jspecify.annotations.Nullable;

/**
 * Toggle Sprint: a press of the sprint key latches sprinting on until the next press, or the player
 * always sprints; the sneak key can latch the same way. A line on the HUD says what's latched.
 *
 * <p>It changes only what the movement keys say ({@link Events#MOVEMENT}): whether the player can
 * sprint (hunger, blindness, walking backwards) is still the game's call, as it is for the key.
 */
public final class ToggleSprint extends Module {

    /** How the sprint key works. */
    public enum SprintMode {
        /** As the game has it: sprint while held. */
        HOLD("Hold"),
        /** A press latches sprinting on, the next turns it off. */
        TOGGLE("Toggle"),
        /** Always sprint; the key does nothing more. */
        ALWAYS("Always");

        private final String label;

        SprintMode(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** How the sneak key works. */
    public enum SneakMode {
        /** As the game has it: sneak while held. */
        HOLD("Hold"),
        /** A press latches sneaking on, the next turns it off. */
        TOGGLE("Toggle");

        private final String label;

        SneakMode(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    // Made once: the HUD asks for its text a few dozen times a second.
    static final String SPRINTING_TOGGLED = "[Sprinting (Toggled)]";
    static final String SPRINTING_ALWAYS = "[Sprinting (Always)]";
    static final String SPRINTING_HELD = "[Sprinting (Key Held)]";
    static final String SNEAKING_TOGGLED = "[Sneaking (Toggled)]";
    static final String SNEAKING_HELD = "[Sneaking (Key Held)]";

    private final ChoiceSetting<SprintMode> sprint = settings()
            .choice("sprint", "Sprint key", SprintMode.values(), SprintMode.TOGGLE, SprintMode::label)
            .describedAs("Toggle: press once to sprint, again to stop. Always: sprint whenever you can.");
    private final ChoiceSetting<SneakMode> sneak = settings()
            .choice("sneak", "Sneak key", SneakMode.values(), SneakMode.HOLD, SneakMode::label)
            .describedAs("Toggle: press once to sneak, again to stop.");
    private final BooleanSetting showStatus = settings()
            .bool("show-status", "Show status", true)
            .describedAs("Shows on the HUD when sprint or sneak is toggled or held.");
    private final BooleanSetting customColor = settings()
            .bool("custom-color", "Custom text color", false)
            .describedAs("Off: the status follows the client's theme.")
            .visibleWhen(showStatus::get);
    private final ColorSetting color =
            settings().color("color", "Text color", 0xFFFFFF).visibleWhen(() -> showStatus.get() && customColor.get());

    private boolean sprintLatched;
    private boolean sneakLatched;
    /** What the HUD says, as of the last tick; null for nothing. */
    private @Nullable String status;

    /** Toggle Sprint, off until the player turns it on. */
    public ToggleSprint() {
        super("togglesprint", "Toggle Sprint", "Sprint and sneak without holding the keys.", Category.MOVEMENT);
        hud(new Status());
        on(Events.MOVEMENT, this::onMovementInput);
        on(Events.LEAVE, this::unlatch);
        on(Events.TICK, this::onTick);
        sprint.onChange(this::unlatch);
        sneak.onChange(this::unlatch);
    }

    @Override
    protected void onDisable() {
        unlatch();
    }

    private void unlatch() {
        sprintLatched = false;
        sneakLatched = false;
        status = null;
    }

    private void onMovementInput(MovementInput input) {
        // An odd number of presses since the last tick flips a latch; an even number leaves it.
        // If the game's own toggle option already latches the key, leave it to the game: two
        // latches would fight once the game resets its own (on respawn).
        boolean sprintHeld = input.sprint();
        boolean sprintToggled = false;
        switch (sprint.get()) {
            case HOLD -> sprintLatched = false;
            case TOGGLE -> {
                if (input.sprintToggledByGame()) {
                    sprintLatched = false;
                    sprintToggled = sprintHeld;
                } else {
                    if (input.sprintPresses() % 2 == 1) {
                        sprintLatched = !sprintLatched;
                    }
                    if (sprintLatched) {
                        input.setSprint(true);
                    }
                    sprintToggled = sprintLatched;
                }
            }
            case ALWAYS -> input.setSprint(true);
        }
        boolean sneakHeld = input.sneak();
        boolean sneakToggled;
        if (sneak.get() == SneakMode.TOGGLE && !input.sneakToggledByGame()) {
            if (input.sneakPresses() % 2 == 1) {
                sneakLatched = !sneakLatched;
            }
            if (sneakLatched) {
                input.setSneak(true);
            }
            sneakToggled = sneakLatched;
        } else {
            sneakLatched = false;
            sneakToggled = sneak.get() == SneakMode.TOGGLE && sneakHeld;
        }
        status = statusFor(sprintHeld, sprintToggled, sneakHeld, sneakToggled);
    }

    /** Death lets go of the latches, as the game lets go of its own on respawn. */
    private void onTick() {
        PlayerView player = system().game().player();
        if (player != null && player.isDead()) {
            unlatch();
        }
    }

    private @Nullable String statusFor(
            boolean sprintHeld, boolean sprintToggled, boolean sneakHeld, boolean sneakToggled) {
        // Sneaking first: it stops sprinting, so it's what the player is doing.
        if (sneakToggled) {
            return SNEAKING_TOGGLED;
        }
        if (sneakHeld) {
            return SNEAKING_HELD;
        }
        if (sprint.get() == SprintMode.ALWAYS) {
            return SPRINTING_ALWAYS;
        }
        if (sprintToggled) {
            return SPRINTING_TOGGLED;
        }
        return sprintHeld ? SPRINTING_HELD : null;
    }

    /** What the HUD line says now, or null for nothing. For tests. */
    @Nullable
    String status() {
        return status;
    }

    /** The HUD line. */
    private final class Status extends HudElement {

        /** What it says now; in the HUD editor, the longest common line when nothing's latched. */
        private @Nullable String shown(HudCanvas canvas) {
            String text = status;
            return text == null && canvas.editing() ? SPRINTING_TOGGLED : text;
        }

        Status() {
            super("status", "Status", Anchor.TOP_LEFT, 2, 2);
        }

        @Override
        public boolean isVisible() {
            return showStatus.get() && status != null;
        }

        @Override
        public boolean isPlaceable() {
            return showStatus.get();
        }

        @Override
        public int width(HudCanvas canvas) {
            String text = shown(canvas);
            return text == null ? 0 : canvas.textWidth(text);
        }

        @Override
        public int height(HudCanvas canvas) {
            return canvas.lineHeight();
        }

        @Override
        public void draw(HudCanvas canvas) {
            String text = shown(canvas);
            if (text == null) {
                return;
            }
            if (customColor.get()) {
                canvas.text(text, 0, 0, color.get());
            } else {
                canvas.text(text, 0, 0, Tone.TEXT);
            }
        }
    }
}
