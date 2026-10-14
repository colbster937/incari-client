package incari.framework;

/**
 * A button a module puts on the title screen and the pause menu while it's on (Screenshot
 * Manager's "Screenshots"). The client lays them out in its menus' style.
 *
 * @param id unique among the module's buttons; stable
 * @param label what the button says
 * @param icon the client's icon for it, by name ("camera", "palette"); the client may have none
 * @param action what pressing it does; game thread
 */
public record MenuButton(String id, String label, String icon, Runnable action) {}
