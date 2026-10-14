package incari.framework.game;

import org.jspecify.annotations.Nullable;

/**
 * The fonts the client's text can be drawn in (a theme's font): Incari's own, the game's pixel
 * font, or one of the open fonts in Incari's pack. A theme's share code keeps the font as its
 * {@link #code()}, which never changes.
 */
public enum UiFont {
    RED_HAT_DISPLAY(0, "Red Hat Display"),
    MINECRAFT(1, "Minecraft"),
    INTER(2, "Inter"),
    NUNITO(3, "Nunito"),
    POPPINS(4, "Poppins"),
    JETBRAINS_MONO(5, "JetBrains Mono"),
    MICHROMA(6, "Michroma");

    private final int code;
    private final String label;

    UiFont(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** Its number in a share code. */
    public int code() {
        return code;
    }

    /** The font numbered {@code code}, or null if this client has none so numbered. */
    public static @Nullable UiFont byCode(int code) {
        for (UiFont font : values()) {
            if (font.code == code) {
                return font;
            }
        }
        return null;
    }

    /** What the settings screen calls it. */
    public String label() {
        return label;
    }
}
