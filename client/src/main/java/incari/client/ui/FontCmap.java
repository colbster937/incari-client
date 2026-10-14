package incari.client.ui;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * The codepoints a TrueType or OpenType font has glyphs for, read from its {@code cmap} table: the
 * browser draws a font's text but won't say which characters it covers, and the game asks each
 * provider for that before it draws anything. Reads the Unicode subtables of formats 4 (the basic
 * plane) and 12 (all planes), which every font made in the last twenty years carries.
 */
final class FontCmap {

    private FontCmap() {}

    /** The font's codepoints, or an empty set if it has no Unicode map this reads. */
    static IntSet codepoints(byte[] font) {
        IntSet result = new IntOpenHashSet();
        int cmap = table(font, "cmap");
        if (cmap < 0) {
            return result;
        }
        int count = u16(font, cmap + 2);
        int best = -1;
        int bestFormat = 0;
        for (int i = 0; i < count; i++) {
            int record = cmap + 4 + i * 8;
            int platform = u16(font, record);
            int encoding = u16(font, record + 2);
            int offset = cmap + (int) u32(font, record + 4);
            boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
            if (!unicode || offset + 2 > font.length) {
                continue;
            }
            int format = u16(font, offset);
            // Format 12 covers every plane; prefer it to format 4.
            if ((format == 12 && bestFormat != 12) || (format == 4 && bestFormat == 0)) {
                best = offset;
                bestFormat = format;
            }
        }
        if (bestFormat == 12) {
            int groups = (int) u32(font, best + 12);
            for (int g = 0; g < groups; g++) {
                int group = best + 16 + g * 12;
                long start = u32(font, group);
                long end = Math.min(u32(font, group + 4), 0x10FFFF);
                for (long c = start; c <= end; c++) {
                    result.add((int) c);
                }
            }
        } else if (bestFormat == 4) {
            int segments = u16(font, best + 6) / 2;
            int ends = best + 14;
            int starts = ends + segments * 2 + 2;
            int deltas = starts + segments * 2;
            int rangeOffsets = deltas + segments * 2;
            for (int s = 0; s < segments; s++) {
                int end = u16(font, ends + s * 2);
                int start = u16(font, starts + s * 2);
                int delta = u16(font, deltas + s * 2);
                int rangeOffset = u16(font, rangeOffsets + s * 2);
                for (int c = start; c <= end && c != 0xFFFF; c++) {
                    int glyph;
                    if (rangeOffset == 0) {
                        glyph = (c + delta) & 0xFFFF;
                    } else {
                        int at = rangeOffsets + s * 2 + rangeOffset + (c - start) * 2;
                        glyph = at + 1 < font.length ? u16(font, at) : 0;
                        if (glyph != 0) {
                            glyph = (glyph + delta) & 0xFFFF;
                        }
                    }
                    if (glyph != 0) {
                        result.add(c);
                    }
                }
            }
        }
        return result;
    }

    /** Where the table {@code tag} starts in the font, or -1. */
    private static int table(byte[] font, String tag) {
        if (font.length < 12) {
            return -1;
        }
        int tables = u16(font, 4);
        for (int i = 0; i < tables; i++) {
            int record = 12 + i * 16;
            if (record + 16 > font.length) {
                return -1;
            }
            if (font[record] == tag.charAt(0)
                    && font[record + 1] == tag.charAt(1)
                    && font[record + 2] == tag.charAt(2)
                    && font[record + 3] == tag.charAt(3)) {
                return (int) u32(font, record + 8);
            }
        }
        return -1;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static long u32(byte[] b, int at) {
        return ((long) u16(b, at) << 16) | u16(b, at + 2);
    }
}
