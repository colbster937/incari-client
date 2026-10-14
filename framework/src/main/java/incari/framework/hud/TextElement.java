package incari.framework.hud;

import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ColorSetting;
import org.jspecify.annotations.Nullable;

/**
 * A HUD element that's one line of text, on the theme's glass unless the player turns the
 * background off: FPS, CPS, reach. The player may color the text themselves; otherwise it's drawn
 * in the element's {@link #tone()}.
 *
 * <p>A subclass hands out its text from {@link #text}, made again only when what it says changes
 * (the HUD asks a few dozen times a second).
 */
public abstract class TextElement extends HudElement {

    /** Space between the glass's edge and the text, across. */
    public static final int PAD_X = 4;

    /** Space between the glass's edge and the text, down. */
    public static final int PAD_Y = 3;

    private final BooleanSetting background;
    private final BooleanSetting customColor;
    private final ColorSetting color;

    /** As {@link HudElement#HudElement}. */
    @SuppressWarnings("this-escape") // settings() is final: it hands out the list, nothing overridable runs.
    protected TextElement(String id, String name, Anchor anchor, int offsetX, int offsetY) {
        super(id, name, anchor, offsetX, offsetY);
        background = settings().bool("background", "Background", true).describedAs("Draws a panel behind the text.");
        customColor = settings()
                .bool("custom-color", "Custom color", false)
                .describedAs("Off: the text takes the theme's colors.");
        color = settings().color("color", "Text color", 0xFFFFFF).visibleWhen(customColor::get);
    }

    /**
     * What it says now, or null for nothing (it's then hidden). In the HUD editor ({@link
     * HudCanvas#editing()}) a sample if there's nothing to say.
     */
    protected abstract @Nullable String text(HudCanvas canvas);

    /** The tone its text takes when the player hasn't colored it: {@link Tone#TEXT}. */
    protected Tone tone() {
        return Tone.TEXT;
    }

    /**
     * The widest text it's likely to say ("000 FPS"), so its width holds still as the numbers
     * change; null to follow the text.
     */
    protected @Nullable String widest() {
        return null;
    }

    /** Whether the glass is drawn behind the text. */
    protected final boolean hasBackground() {
        return background.get();
    }

    @Override
    public int width(HudCanvas canvas) {
        String text = text(canvas);
        if (text == null) {
            return 0;
        }
        int width = canvas.textWidth(text);
        String widest = widest();
        if (widest != null) {
            width = Math.max(width, canvas.textWidth(widest));
        }
        return width + (background.get() ? PAD_X * 2 : 0);
    }

    @Override
    public int height(HudCanvas canvas) {
        // The font's line height has a pixel of space below the letters: one pad less below.
        return canvas.lineHeight() + (background.get() ? PAD_Y * 2 - 1 : 0);
    }

    @Override
    public void draw(HudCanvas canvas) {
        String text = text(canvas);
        if (text == null) {
            return;
        }
        int width = width(canvas);
        int x = 0;
        int y = 0;
        if (background.get()) {
            canvas.fill(0, 0, width, height(canvas), Tone.BACKGROUND);
            x = PAD_X;
            y = PAD_Y;
        }
        // Centered in the width {@link #widest} holds.
        x += (width - (background.get() ? PAD_X * 2 : 0) - canvas.textWidth(text)) / 2;
        if (customColor.get()) {
            canvas.text(text, x, y, color.get());
        } else {
            canvas.text(text, x, y, tone());
        }
    }
}
