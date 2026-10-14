package incari.client.look;

import incari.client.ui.Icons;
import org.jspecify.annotations.Nullable;

/** The icons modules name for their menu buttons ({@code MenuButton.icon}). */
final class MenuIcons {

    private MenuIcons() {}

    /** The icon called {@code name}, or null for none. */
    static @Nullable Icons of(String name) {
        return switch (name) {
            case "camera" -> Icons.CAMERA;
            case "palette" -> Icons.PALETTE;
            case "grid" -> Icons.GRID;
            case "layout" -> Icons.LAYOUT;
            case "incari" -> Icons.INCARI;
            default -> null;
        };
    }
}
