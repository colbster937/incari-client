package incari.framework.game;

/**
 * The client's look as a few colors ({@link Game#applyTheme}): every other shade is made from these.
 * Colors are {@code 0xRRGGBB}.
 *
 * @param accent what's on, chosen or focused
 * @param text ordinary text
 * @param muted secondary text
 * @param panel the panels' and the HUD's glass
 * @param panelOpacity the menus' panels, 0 to 100 percent (where they aren't blurred behind)
 * @param hudOpacity the HUD's glass, 0 to 100 percent
 * @param radius a control's corner radius in GUI units
 */
public record ThemeColors(int accent, int text, int muted, int panel, int panelOpacity, int hudOpacity, int radius) {

    /** Frost, the client's own. */
    public static final ThemeColors FROST = new ThemeColors(0xA9DCFF, 0xFFFFFF, 0xB4BDC6, 0x242A32, 86, 42, 3);
}
