package incari.client.render;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.ActiveArea;
import net.minecraft.client.gui.font.EmptyArea;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.jspecify.annotations.Nullable;

/**
 * Laid-out text in the world, kept from one frame to the next (an allocation fix, ADR 0025).
 * The game lays every sign line, name tag and text display out again each frame: a builder, its
 * list of glyphs and an object per glyph, a few dozen objects a line. A crowded lobby has hundreds
 * of lines, and they were most of what its frames left for the garbage collector. The text hardly
 * ever changes, but its objects do (an entity's name is a new component every frame), so a layout is
 * kept by what the text says (its characters and their styles, read without allocating anything)
 * and the position, colours and shadow it was laid out with, and drawn again as it was: the same
 * glyphs in the same places, since nothing else goes into a layout.
 *
 * <p>Not kept: text with an obfuscated part (each layout picks new random glyphs for it), and
 * anything after the fonts change (a resource reload or a font option clears it all). The least
 * recently drawn layouts go once {@value #CAPACITY} are kept. Render thread only.
 */
public final class TextLayouts {

    /** Layouts kept at most: a lobby full of signs and names draws about a thousand. */
    static final int CAPACITY = 2048;

    /** One layout, and what it was laid out with. */
    private record Layout(
            Font font, float x, float y, int color, boolean shadow, int background, Font.PreparedText text) {
        boolean matches(Font font, float x, float y, int color, boolean shadow, int background) {
            return this.font.equals(font)
                    && this.x == x
                    && this.y == y
                    && this.color == color
                    && this.shadow == shadow
                    && this.background == background;
        }
    }

    /**
     * What a text says: its characters and the style of each, as far as the style shapes the layout
     * (its colours, weight, slant, lines and font; not the hover and click events a name carries,
     * which only the chat reads). The one being read is reused for every lookup; a copy of it is made
     * only to keep a new layout by.
     */
    private static final class Content implements FormattedCharSink {
        int[] codepoints;
        Style[] styles;
        int length;
        int hash;
        private @Nullable Style lastStyle;
        private int lastStyleHash;

        Content(int capacity) {
            codepoints = new int[capacity];
            styles = new Style[capacity];
        }

        void read(FormattedCharSequence text) {
            length = 0;
            hash = 1;
            lastStyle = null;
            text.accept(this);
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // A run's characters share one style object: the cheap test.
        public boolean accept(int position, Style style, int codepoint) {
            if (length == codepoints.length) {
                codepoints = Arrays.copyOf(codepoints, length * 2);
                styles = Arrays.copyOf(styles, length * 2);
            }
            codepoints[length] = codepoint;
            styles[length] = style;
            length++;
            if (style != lastStyle) {
                // A run of characters shares its style: hashed once a run.
                lastStyle = style;
                lastStyleHash = layoutHash(style);
            }
            hash = 31 * (31 * hash + codepoint) + lastStyleHash;
            return true;
        }

        Content copy() {
            Content copy = new Content(length);
            System.arraycopy(codepoints, 0, copy.codepoints, 0, length);
            System.arraycopy(styles, 0, copy.styles, 0, length);
            copy.length = length;
            copy.hash = hash;
            return copy;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Content content) || content.length != length || content.hash != hash) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (codepoints[i] != content.codepoints[i]) {
                    return false;
                }
            }
            for (int i = 0; i < length; i++) {
                if (!sameLayout(styles[i], content.styles[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final Content READING = new Content(64);

    private static volatile boolean enabled = true;

    /** A hash of what in {@code style} shapes a layout. Allocates nothing (hashing a whole style can). */
    static int layoutHash(Style style) {
        TextColor color = style.getColor();
        Integer shadow = style.getShadowColor();
        int hash = color == null ? 0 : color.getValue() + 1;
        hash = 31 * hash + (shadow == null ? 0 : shadow + 1);
        hash = 31 * hash
                + (style.isBold() ? 1 : 0)
                + (style.isItalic() ? 2 : 0)
                + (style.isUnderlined() ? 4 : 0)
                + (style.isStrikethrough() ? 8 : 0)
                + (style.isObfuscated() ? 16 : 0);
        return 31 * hash + style.getFont().hashCode();
    }

    /** Whether text in {@code a} and in {@code b} lays out the same. */
    static boolean sameLayout(Style a, Style b) {
        return Objects.equals(a.getColor(), b.getColor())
                && Objects.equals(a.getShadowColor(), b.getShadowColor())
                && a.isBold() == b.isBold()
                && a.isItalic() == b.isItalic()
                && a.isUnderlined() == b.isUnderlined()
                && a.isStrikethrough() == b.isStrikethrough()
                && a.isObfuscated() == b.isObfuscated()
                && a.getFont().equals(b.getFont());
    }

    // A text drawn two ways in a frame (a name tag's see-through pass and its normal one) keeps both.
    private static final LinkedHashMap<Content, Layout[]> LAYOUTS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Content, Layout[]> eldest) {
            return size() > CAPACITY;
        }
    };

    private TextLayouts() {}

    /**
     * Hook ({@code TextFeatureRenderer.renderText}): {@code text} laid out as the game's
     * {@code font.prepareText(text, x, y, color, shadow, false, background)} lays it out, the same
     * layout as last time if text saying the same has been drawn so before.
     */
    public static Font.PreparedText prepare(
            Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, int background) {
        if (!enabled) {
            return font.prepareText(text, x, y, color, shadow, false, background);
        }
        READING.read(text);
        Layout[] layouts = LAYOUTS.get(READING);
        if (layouts != null) {
            for (Layout layout : layouts) {
                if (layout.matches(font, x, y, color, shadow, background)) {
                    return layout.text();
                }
            }
        }
        Font.PreparedText prepared = font.prepareText(text, x, y, color, shadow, false, background);
        if (!obfuscated(prepared)) {
            Layout layout = new Layout(font, x, y, color, shadow, background, prepared);
            if (layouts == null || layouts.length >= 4) {
                LAYOUTS.put(READING.copy(), new Layout[] {layout});
            } else {
                Layout[] more = Arrays.copyOf(layouts, layouts.length + 1);
                more[layouts.length] = layout;
                LAYOUTS.put(READING.copy(), more);
            }
        }
        return prepared;
    }

    /** Whether layouts are kept (the page's {@code ?text=game} turns it off, to compare). */
    public static void setEnabled(boolean keep) {
        enabled = keep;
        LAYOUTS.clear();
    }

    /** Hook (a resource reload, a font option): the fonts may have changed; every layout goes. */
    public static void clear() {
        LAYOUTS.clear();
    }

    /** Layouts kept now. */
    static int size() {
        return LAYOUTS.size();
    }

    private static boolean obfuscated(Font.PreparedText prepared) {
        boolean[] found = {false};
        prepared.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptGlyph(TextRenderable.Styled glyph) {
                found[0] |= isObfuscated(glyph);
            }

            @Override
            public void acceptEmptyArea(EmptyArea area) {
                found[0] |= isObfuscated(area);
            }
        });
        return found[0];
    }

    private static boolean isObfuscated(@Nullable ActiveArea area) {
        return area != null && area.style().isObfuscated();
    }
}
