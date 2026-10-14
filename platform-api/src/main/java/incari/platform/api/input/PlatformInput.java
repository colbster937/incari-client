package incari.platform.api.input;

import org.jspecify.annotations.Nullable;

/** Keyboard state and key names. Events are delivered through {@code Platform.pollEvents}. */
public interface PlatformInput {

    /**
     * Whether the key is held right now, as of the last {@code pollEvents}.
     *
     * @param scancode a {@link Scancodes} value; out-of-range values are simply not down
     */
    boolean isKeyDown(int scancode);

    /** The modifiers held right now, as {@link KeyModifiers} bits. */
    int modifiers();

    /**
     * What the key is labelled in the player's current keyboard layout, for key binding screens:
     * "Z" for the key at {@link Scancodes#W} on a French keyboard.
     *
     * @return the label, or null if the platform has none for this key
     */
    @Nullable
    String keyName(int scancode);
}
