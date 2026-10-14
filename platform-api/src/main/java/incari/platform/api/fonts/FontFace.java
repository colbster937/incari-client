package incari.platform.api.fonts;

import org.jspecify.annotations.Nullable;

/** A font opened at one size. Not thread-safe: one thread draws at a time. */
public interface FontFace extends AutoCloseable {

    /**
     * Draws {@code codepoint}, or returns null if the font has no glyph for it (a platform that
     * can't tell answers with the font's fallback glyph; callers ask only for the font's own).
     */
    @Nullable
    Glyph glyph(int codepoint);

    @Override
    void close();

    /**
     * A drawn glyph, in pixels, as FreeType gives it: {@code left} from the pen to the bitmap's left
     * edge, {@code top} from the baseline up to its top edge, and the pen's advance. An empty glyph
     * (a space) has no pixels. Its coverage is {@code width * height} bytes, row by row from the
     * top, 0 transparent to 255 opaque.
     */
    final class Glyph {
        private final float advance;
        private final int left;
        private final int top;
        private final int width;
        private final int height;
        private final byte[] coverage;

        public Glyph(float advance, int left, int top, int width, int height, byte[] coverage) {
            this.advance = advance;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.coverage = coverage;
        }

        public float advance() {
            return advance;
        }

        public int left() {
            return left;
        }

        public int top() {
            return top;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The pixels' coverage, the glyph's own array: don't change it. */
        @SuppressWarnings("EffectivelyPrivate")
        public byte[] coverage() {
            return coverage;
        }
    }
}
