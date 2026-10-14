package incari.features.theme;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.game.ThemeColors;
import incari.framework.game.UiFont;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.IntSetting;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Theme Editor: the client's colors and font, menus and HUD alike. Pick a preset, or change the
 * accent, the text, the panels, how see-through they are and the font one by one, live; share a
 * theme as a code another player pastes in. Part of the client, not a module the player turns on
 * ({@link #alwaysOn()}): the module menu's sidebar opens it, and Frost is its first preset.
 */
public final class ThemeEditor extends Module {

    /** Ready-made themes: each sets the colors below. */
    public enum Preset {
        CUSTOM("Custom", null),
        FROST("Frost", ThemeColors.FROST),
        MIDNIGHT("Midnight", new ThemeColors(0x8B9CFF, 0xF2F4FF, 0xA3A9C7, 0x161A2E, 88, 45, 3)),
        EMBER("Ember", new ThemeColors(0xFFB067, 0xFFF6EE, 0xC9B8A8, 0x2E1F1A, 86, 42, 3)),
        MINT("Mint", new ThemeColors(0x7FE8C3, 0xF4FFFA, 0xA7C4B9, 0x18291F, 86, 42, 4)),
        ROSE("Rose", new ThemeColors(0xFF9EC4, 0xFFF5F9, 0xCDB0BC, 0x2E1C25, 86, 42, 5)),
        MONO("Mono", new ThemeColors(0xE6E6E6, 0xFFFFFF, 0xA0A0A0, 0x1A1A1A, 90, 50, 1));

        private final String label;

        @SuppressWarnings("ImmutableEnumChecker") // A record of ints: nothing in it changes.
        private final @Nullable ThemeColors colors;

        Preset(String label, @Nullable ThemeColors colors) {
            this.label = label;
            this.colors = colors;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }

        /** Its colors; null for Custom. */
        public @Nullable ThemeColors colors() {
            return colors;
        }
    }

    /** What a share code starts with. */
    static final String CODE_PREFIX = "incari-theme:";

    final ChoiceSetting<Preset> preset = settings()
            .choice("preset", "Preset", Preset.values(), Preset.FROST, Preset::label)
            .describedAs("Loads a preset theme into the colors below.");
    final ColorSetting accent = settings()
            .color("accent", "Accent", ThemeColors.FROST.accent())
            .describedAs("What's on, chosen or focused.");
    final ColorSetting text = settings().color("text", "Text", ThemeColors.FROST.text());
    final ColorSetting muted = settings()
            .color("muted", "Secondary text", ThemeColors.FROST.muted())
            .describedAs("Descriptions, labels, what's off.");
    final ColorSetting panel = settings()
            .color("panel", "Panels", ThemeColors.FROST.panel())
            .describedAs("Menu panels and the HUD's background.");
    final IntSetting panelOpacity = settings()
            .integer("panel-opacity", "Panel opacity", 40, 100, ThemeColors.FROST.panelOpacity())
            .formattedAs(percent -> percent + "%")
            .describedAs("How solid the menus' panels are (Potato GUI; Normal blurs behind them).");
    final IntSetting hudOpacity = settings()
            .integer("hud-opacity", "HUD background opacity", 0, 100, ThemeColors.FROST.hudOpacity())
            .formattedAs(percent -> percent + "%")
            .describedAs("How solid the background behind HUD text is.");
    final IntSetting radius = settings()
            .integer("radius", "Corner rounding", 0, 8, ThemeColors.FROST.radius())
            .describedAs("How round buttons and panels are.");
    final ChoiceSetting<UiFont> font = settings()
            .choice("font", "Font", UiFont.values(), UiFont.RED_HAT_DISPLAY, UiFont::label)
            .describedAs("The text's font, in the menus, the HUD and the game.");

    private @Nullable ThemeColors applied;
    private @Nullable UiFont appliedFont;

    /** Theme Editor, always on: Frost until the player picks another. */
    public ThemeEditor() {
        super("theme", "Theme Editor", "Changes the client's colors and font: presets, or your own.", Category.VISUAL);
        alwaysOn();
        preset.onChange(this::applyPreset);
        settings()
                .action("copy-code", "Export theme", "Copy theme code", this::copyCode)
                .describedAs("Copies this theme as a code other players can paste.");
        settings()
                .action("paste-code", "Import theme", "Paste theme code", this::pasteCode)
                .describedAs("Loads a theme code another player shared.");
        on(Events.TICK, this::tick);
    }

    /** The theme the settings make. */
    ThemeColors colors() {
        return new ThemeColors(
                accent.get() & 0xFFFFFF,
                text.get() & 0xFFFFFF,
                muted.get() & 0xFFFFFF,
                panel.get() & 0xFFFFFF,
                panelOpacity.get(),
                hudOpacity.get(),
                radius.get());
    }

    /** The preset's name, or Custom: what the module menu's sidebar shows beside "Theme". */
    public String label() {
        return preset.get().label();
    }

    private void applyPreset() {
        ThemeColors colors = preset.get().colors();
        if (colors != null) {
            set(colors);
        }
    }

    private void set(ThemeColors colors) {
        accent.set(colors.accent());
        text.set(colors.text());
        muted.set(colors.muted());
        panel.set(colors.panel());
        panelOpacity.set(colors.panelOpacity());
        hudOpacity.set(colors.hudOpacity());
        radius.set(colors.radius());
    }

    @Override
    protected void onEnable() {
        applied = null;
        appliedFont = null;
        tick();
    }

    @Override
    protected void onDisable() {
        // Only after a failure: back to Frost and Incari's font.
        applied = null;
        appliedFont = null;
        system().game().applyTheme(null);
        system().game().applyFont(UiFont.RED_HAT_DISPLAY);
    }

    /** Applies the settings when they've changed: a color field's typing shows at once. */
    private void tick() {
        if (appliedFont != font.get()) {
            appliedFont = font.get();
            system().game().applyFont(font.get());
        }
        ThemeColors current = applied;
        if (current != null
                && current.accent() == (accent.get() & 0xFFFFFF)
                && current.text() == (text.get() & 0xFFFFFF)
                && current.muted() == (muted.get() & 0xFFFFFF)
                && current.panel() == (panel.get() & 0xFFFFFF)
                && current.panelOpacity() == panelOpacity.get()
                && current.hudOpacity() == hudOpacity.get()
                && current.radius() == radius.get()) {
            return;
        }
        ThemeColors colors = colors();
        applied = colors;
        system().game().applyTheme(colors);
        ThemeColors chosen = preset.get().colors();
        if (chosen != null && !chosen.equals(colors)) {
            // A color changed by hand: the theme is the player's own now.
            preset.set(Preset.CUSTOM);
        }
    }

    private void copyCode() {
        system().game().copy(encode(colors(), font.get()));
        system().game().showMessage("Theme", "Theme code copied. Paste it to share your theme.");
    }

    /**
     * Asks for the code in a field the player pastes into: in the browser a button can't read the
     * clipboard, only a paste brings its text. A code already on a clipboard the client can read
     * (the desktop's) fills the field.
     */
    private void pasteCode() {
        String clipboard = system().game().clipboard();
        system().game()
                .askText(
                        "Import theme",
                        "Paste a theme code (Ctrl+V), then press Done.",
                        decode(clipboard) != null ? clipboard.strip() : "",
                        this::importCode);
    }

    private void importCode(String text) {
        Shared shared = decode(text);
        if (shared == null) {
            system().game().showMessage("Theme", "That isn't a theme code.");
            return;
        }
        preset.set(Preset.CUSTOM);
        set(shared.colors());
        if (shared.font() != null) {
            font.set(shared.font());
        }
        system().game().showMessage("Theme", "Theme loaded.");
    }

    /** A theme read from a share code; {@code font} is null in a code from before fonts. */
    record Shared(ThemeColors colors, @Nullable UiFont font) {}

    /**
     * "incari-theme:a9dcff-ffffff-b4bdc6-242a32-56-2a-3-0": the colors, then the numbers, then the
     * font's {@linkplain UiFont#code() code}, in hex.
     */
    static String encode(ThemeColors colors, UiFont font) {
        return CODE_PREFIX
                + hex6(colors.accent()) + "-" + hex6(colors.text()) + "-" + hex6(colors.muted()) + "-"
                + hex6(colors.panel()) + "-" + Integer.toHexString(colors.panelOpacity()) + "-"
                + Integer.toHexString(colors.hudOpacity()) + "-" + Integer.toHexString(colors.radius()) + "-"
                + Integer.toHexString(font.code());
    }

    private static String hex6(int rgb) {
        String hex = Integer.toHexString(rgb & 0xFFFFFF);
        while (hex.length() < 6) {
            hex = "0" + hex;
        }
        return hex;
    }

    /**
     * The theme {@link #encode} wrote, found anywhere in {@code text}; null if there's none. A code
     * without a font (from before fonts), or with one this client doesn't have, keeps the font.
     */
    static @Nullable Shared decode(String text) {
        int start = text.toLowerCase(Locale.ROOT).indexOf(CODE_PREFIX);
        if (start < 0) {
            return null;
        }
        String rest = text.substring(start + CODE_PREFIX.length()).strip();
        int end = 0;
        while (end < rest.length() && (Character.digit(rest.charAt(end), 16) >= 0 || rest.charAt(end) == '-')) {
            end++;
        }
        String code = rest.substring(0, end);
        String[] parts = code.split("-", -1);
        if (parts.length != 7 && parts.length != 8) {
            return null;
        }
        int[] values = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                values[i] = Integer.parseInt(parts[i], 16);
            }
            UiFont shared = parts.length == 8 ? UiFont.byCode(values[7]) : null;
            return new Shared(
                    new ThemeColors(
                            values[0] & 0xFFFFFF,
                            values[1] & 0xFFFFFF,
                            values[2] & 0xFFFFFF,
                            values[3] & 0xFFFFFF,
                            Math.max(40, Math.min(100, values[4])),
                            Math.max(0, Math.min(100, values[5])),
                            Math.max(0, Math.min(8, values[6]))),
                    shared);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
