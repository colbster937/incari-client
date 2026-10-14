package incari.framework.event;

/**
 * The events a module can listen to. Each is fired on the game thread by one hook in the game.
 *
 * <p>Events are in the game's terms but carry no game types: what a listener gets is defined here
 * and filled in by {@code :client}. A new event comes with the first module that needs it, with
 * its hook (ADR 0046 lists what's planned).
 */
public final class Events {

    /** Once a client tick (20 a second), in or out of a world, before the game reads its keybinds. */
    public static final Channel<TickListener> TICK = new Channel<>(0, "tick");

    /**
     * Once a tick while the player is in a world, after the game reads the movement keys and before
     * the player moves: listeners may change what the keys say ({@link MovementInput}).
     */
    public static final Channel<MovementListener> MOVEMENT = new Channel<>(1, "movement");

    /** When the player arrives in a world: a server's, or singleplayer's. */
    public static final Channel<JoinListener> JOIN = new Channel<>(2, "join");

    /** When the player leaves a world (to the title screen or another server). */
    public static final Channel<LeaveListener> LEAVE = new Channel<>(3, "leave");

    /**
     * Each frame the fire overlay is drawn (the player burning, in first person), before it's
     * drawn: listeners may move it ({@link FireOverlay}). Render thread.
     */
    public static final Channel<FireOverlayListener> FIRE_OVERLAY = new Channel<>(4, "fire-overlay");

    /**
     * Each frame with the HUD shown, before the game's HUD and with the crosshair: listeners draw
     * what follows the world on screen (a waypoint's label) on a {@link
     * incari.framework.hud.WorldOverlay}. Drawn every frame, not with the HUD's refreshes. Render
     * thread; a hot path.
     */
    public static final Channel<OverlayListener> OVERLAY = new Channel<>(5, "overlay");

    /**
     * Each frame the world is drawn, while it's put together: listeners add to it (a waypoint's
     * beam) on a {@link incari.framework.hud.WorldCanvas}. Render thread; a hot path.
     */
    public static final Channel<WorldListener> WORLD = new Channel<>(6, "world");

    /**
     * When a mouse button goes down over the game with no screen open (CPS counts them). Game
     * thread, as the press arrives.
     */
    public static final Channel<ClickListener> CLICK = new Channel<>(7, "click");

    /** When the player attacks an entity: how far the hit reached (Reach Display). Game thread. */
    public static final Channel<AttackListener> ATTACK = new Channel<>(8, "attack");

    /**
     * Each frame the game would draw its crosshair (first person, the HUD shown): listeners draw
     * their own ({@link Crosshair}); while anyone listens, the game's isn't drawn. Render thread; a
     * hot path.
     */
    public static final Channel<CrosshairListener> CROSSHAIR = new Channel<>(9, "crosshair");

    /**
     * When the mouse turns the view over the world: a listener may take the turn ({@link Turn}).
     * Game thread, as the mouse moves.
     */
    public static final Channel<TurnListener> TURN = new Channel<>(10, "turn");

    /**
     * Each frame the camera is placed: listeners may turn it without turning the player ({@link
     * CameraAngles}). Render thread; a hot path.
     */
    public static final Channel<CameraListener> CAMERA = new Channel<>(11, "camera");

    /**
     * Each frame, for each hand, before its item is drawn in first person: listeners say how
     * ({@link HeldItem}). Render thread; a hot path.
     */
    public static final Channel<HeldItemListener> HELD_ITEM = new Channel<>(12, "held-item");

    /**
     * When the tooltip of an item holding others (a shulker box) is made: listeners may show the
     * contents as a grid ({@link ContainerTooltip}). Render thread, as the pointer hovers.
     */
    public static final Channel<ContainerTooltipListener> CONTAINER_TOOLTIP = new Channel<>(13, "container-tooltip");

    /** When a screenshot (F2) has been saved. Game thread. */
    public static final Channel<ScreenshotListener> SCREENSHOT = new Channel<>(14, "screenshot");

    /**
     * When an entity the player hit in the last few seconds dies (Hit Effects' kill effect). Game
     * thread, on the tick the client sees it die.
     */
    public static final Channel<KillListener> KILL = new Channel<>(15, "kill");

    static final int COUNT = 16;

    private Events() {}
}
