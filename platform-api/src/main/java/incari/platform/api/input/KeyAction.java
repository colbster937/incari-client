package incari.platform.api.input;

/** What happened to a key. */
public enum KeyAction {
    PRESS,
    RELEASE,
    /** The key is held and the system's auto-repeat fired. */
    REPEAT
}
