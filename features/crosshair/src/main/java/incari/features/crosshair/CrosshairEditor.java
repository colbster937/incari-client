package incari.features.crosshair;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Crosshair;
import incari.framework.event.Events;
import incari.framework.hud.HudCanvas;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.GridSetting;
import incari.framework.setting.IntSetting;

/**
 * Crosshair Editor: the crosshair the player designs, in place of the game's. A cross, a T, a
 * circle or a dot from sliders measured in screen pixels, with an outline and a center dot; or a
 * 15 by 15 grid drawn cell by cell. Presets fill the sliders in. It can change color while the
 * crosshair is on a mob or player in reach.
 */
public final class CrosshairEditor extends Module {

    /** What's drawn. */
    public enum Style {
        CROSS("Cross"),
        /** A cross without its top arm. */
        T("T"),
        CIRCLE("Circle"),
        /** The center dot alone. */
        DOT("Dot"),
        /** The pixel grid, cell by cell. */
        GRID("Pixel grid");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** Ready-made crosshairs: each sets the sliders, which the player may then change. */
    public enum Preset {
        CUSTOM("Custom", Style.CROSS, 0, 0, 0, false, false),
        CLASSIC("Classic", Style.CROSS, 9, 0, 2, false, false),
        COMPETITIVE("Competitive", Style.CROSS, 6, 3, 2, false, true),
        SMALL("Small", Style.CROSS, 4, 2, 1, false, true),
        DOT("Dot", Style.DOT, 0, 0, 4, true, true),
        CIRCLE("Circle", Style.CIRCLE, 0, 8, 2, true, true),
        T_SHAPE("T", Style.T, 7, 3, 2, false, true);

        private final String label;
        private final Style style;
        private final int length;
        private final int gap;
        private final int thickness;
        private final boolean dot;
        private final boolean outline;

        Preset(String label, Style style, int length, int gap, int thickness, boolean dot, boolean outline) {
            this.label = label;
            this.style = style;
            this.length = length;
            this.gap = gap;
            this.thickness = thickness;
            this.dot = dot;
            this.outline = outline;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** The grid's side: the game's crosshair is 15 GUI units across. */
    static final int GRID = 15;

    /** A plus five cells a side with a clear middle, as the grid starts. */
    static final long[] DEFAULT_GRID = defaultGrid();

    final ChoiceSetting<Preset> preset = settings()
            .choice("preset", "Preset", Preset.values(), Preset.CLASSIC, Preset::label)
            .describedAs("Loads a preset crosshair into the sliders below.");
    final ChoiceSetting<Style> style = settings()
            .choice("style", "Style", Style.values(), Style.CROSS, Style::label)
            .describedAs("A cross, a T, a circle, a dot, or the pixel grid you draw.");
    final GridSetting grid = settings()
            .grid("grid", "Pixel grid", GRID, DEFAULT_GRID)
            .describedAs("Draw your own crosshair, a cell a GUI pixel.")
            .visibleWhen(() -> style.get() == Style.GRID);
    final IntSetting length = settings()
            .integer("length", "Length", 1, 30, 9)
            .formattedAs(pixels -> pixels + " px")
            .describedAs("How long each arm is, in screen pixels.")
            .visibleWhen(() -> style.get() == Style.CROSS || style.get() == Style.T);
    final IntSetting gap = settings()
            .integer("gap", "Gap", 0, 20, 0)
            .formattedAs(pixels -> pixels + " px")
            .describedAs("Space between the middle and each arm; a circle's radius.")
            .visibleWhen(() -> style.get() != Style.DOT && style.get() != Style.GRID);
    final IntSetting thickness = settings()
            .integer("thickness", "Thickness", 1, 10, 2)
            .formattedAs(pixels -> pixels + " px")
            .describedAs("How thick the lines are, and the dot's size.")
            .visibleWhen(() -> style.get() != Style.GRID);
    final BooleanSetting dot = settings()
            .bool("dot", "Center dot", false)
            .describedAs("Adds a dot in the middle.")
            .visibleWhen(() -> style.get() != Style.DOT && style.get() != Style.GRID);
    final BooleanSetting outline = settings()
            .bool("outline", "Outline", false)
            .describedAs("A dark outline that keeps the crosshair visible on snow and sky.");
    final ColorSetting color = settings().color("color", "Color", 0xFFFFFF);
    final IntSetting opacity =
            settings().integer("opacity", "Opacity", 10, 100, 100).formattedAs(percent -> percent + "%");
    final BooleanSetting targetColor = settings()
            .bool("target-color", "Color on target", false)
            .describedAs("Changes color while the crosshair is on a mob or player in reach.");
    final ColorSetting onTargetColor =
            settings().color("on-target-color", "Target color", 0xFF5555).visibleWhen(targetColor::get);

    /** Whether a preset is filling the sliders in: their changes are its, not the player's. */
    private boolean applying;

    /** Crosshair Editor, off until the player turns it on. */
    public CrosshairEditor() {
        super("crosshair", "Crosshair Editor", "Draws a crosshair of your own design.", Category.VISUAL);
        preset.onChange(this::applyPreset);
        // A slider moved away from the preset makes it Custom, so picking the preset again loads it.
        style.onChange(this::followSliders);
        length.onChange(this::followSliders);
        gap.onChange(this::followSliders);
        thickness.onChange(this::followSliders);
        dot.onChange(this::followSliders);
        outline.onChange(this::followSliders);
        on(Events.CROSSHAIR, this::draw);
    }

    private void applyPreset() {
        Preset chosen = preset.get();
        if (chosen == Preset.CUSTOM) {
            return;
        }
        applying = true;
        try {
            style.set(chosen.style);
            length.set(Math.max(1, chosen.length));
            gap.set(chosen.gap);
            thickness.set(chosen.thickness);
            dot.set(chosen.dot);
            outline.set(chosen.outline);
        } finally {
            applying = false;
        }
    }

    private void followSliders() {
        Preset chosen = preset.get();
        if (!applying && chosen != Preset.CUSTOM && !shows(chosen)) {
            preset.set(Preset.CUSTOM);
        }
    }

    /** Whether the sliders are as {@code chosen} sets them. */
    private boolean shows(Preset chosen) {
        return style.get() == chosen.style
                && length.get() == Math.max(1, chosen.length)
                && gap.get() == chosen.gap
                && thickness.get() == chosen.thickness
                && dot.get() == chosen.dot
                && outline.get() == chosen.outline;
    }

    private static long[] defaultGrid() {
        long[] rows = new long[GRID];
        int middle = GRID / 2;
        for (int i = 2; i <= 6; i++) {
            rows[middle] |= 1L << (middle - i) | 1L << (middle + i);
            rows[middle - i] |= 1L << middle;
            rows[middle + i] |= 1L << middle;
        }
        return rows;
    }

    private void draw(Crosshair crosshair) {
        HudCanvas canvas = crosshair.canvas();
        int alpha = Math.round(opacity.get() * 2.55F) << 24;
        int rgb = (targetColor.get() && crosshair.onTarget() ? onTargetColor.get() : color.get()) & 0xFFFFFF;
        int argb = alpha | rgb;
        int shade = alpha | 0x000000;
        if (style.get() == Style.GRID) {
            // In GUI units, as the game's own crosshair is drawn.
            canvas.push(crosshair.centerX() - GRID / 2.0F, crosshair.centerY() - GRID / 2.0F, 1);
            try {
                drawGrid(canvas, argb, shade);
            } finally {
                canvas.pop();
            }
            return;
        }
        // In screen pixels, around the middle.
        canvas.push(crosshair.centerX(), crosshair.centerY(), crosshair.pixel());
        try {
            if (outline.get()) {
                shapes(canvas, shade, 1);
            }
            shapes(canvas, argb, 0);
        } finally {
            canvas.pop();
        }
    }

    /** Every line of the style, grown by {@code grow} pixels each side (the outline). */
    private void shapes(HudCanvas canvas, int argb, int grow) {
        int t = thickness.get();
        int half = t / 2;
        int g = gap.get();
        int l = length.get();
        switch (style.get()) {
            case CROSS, T -> {
                rect(canvas, -g - l, -half, l, t, grow, argb);
                rect(canvas, g + (t - half * 2), -half, l, t, grow, argb);
                rect(canvas, -half, g + (t - half * 2), t, l, grow, argb);
                if (style.get() == Style.CROSS) {
                    rect(canvas, -half, -g - l, t, l, grow, argb);
                }
            }
            case CIRCLE -> {
                // In pixels too: the canvas is scaled to them.
                canvas.ring(0, 0, g + t + grow, t + grow * 2, argb);
            }
            case DOT, GRID -> {}
        }
        if (dot.get() || style.get() == Style.DOT) {
            rect(canvas, -half, -half, t, t, grow, argb);
        }
    }

    private static void rect(HudCanvas canvas, int x, int y, int w, int h, int grow, int argb) {
        canvas.fill(x - grow, y - grow, w + grow * 2, h + grow * 2, argb);
    }

    private void drawGrid(HudCanvas canvas, int argb, int shade) {
        if (outline.get()) {
            // Each row's outline: the cells beside, above and below a lit one, a column out on each
            // side (bit x + 1 is cell x), drawn as runs like the cells. Drawn every frame: a run is
            // one quad where a cell's four sides were four.
            for (int y = -1; y <= GRID; y++) {
                long around = shifted(y - 1) | shifted(y + 1) | shifted(y) << 1 | shifted(y) >>> 1;
                runs(canvas, around, y, -1, shade);
            }
        }
        for (int y = 0; y < GRID; y++) {
            runs(canvas, grid.row(y), y, 0, argb);
        }
    }

    /** Row {@code y} of the grid with cell x at bit x + 1; nothing outside it. */
    private long shifted(int y) {
        return y < 0 || y >= GRID ? 0 : grid.row(y) << 1;
    }

    /** The set bits of {@code row} as fills, a run of them in one: bit i is cell {@code i + first}. */
    private static void runs(HudCanvas canvas, long row, int y, int first, int argb) {
        for (int i = 0; i < Long.SIZE; i++) {
            if ((row >>> i & 1L) != 0) {
                int run = 1;
                while (i + run < Long.SIZE && (row >>> (i + run) & 1L) != 0) {
                    run++;
                }
                canvas.fill(i + first, y, run, 1, argb);
                i += run - 1;
            }
        }
    }
}
