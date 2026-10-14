package incari.client.memory;

import com.mojang.blaze3d.font.UnbakedGlyph;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.jspecify.annotations.Nullable;

/**
 * The Unicode font's glyphs packed into arrays, each made again as an object when the font asks
 * for it. The game's unihex provider keeps a glyph as three objects (the glyph, its lines and their
 * array) for each of about 271,000 codepoints: 27 MB of the page's heap in a heap snapshot, kept for
 * the whole session, for glyphs a player mostly never sees. Here a glyph is its sixteen lines, each
 * stored in as many bytes as the glyph is wide (one for 8 pixels, two for 16, three for 24, four for
 * 32), and its left and right edges: about 9 MB in all. The font set asks for each glyph a few times
 * while it loads and caches what it makes, so making one again is no hot path.
 *
 * <p>A line is read back exactly as the game's own contents give it: the stored bits shifted to the
 * top of the int, as {@code ByteContents}, {@code ShortContents} and {@code IntContents} keep them.
 */
public final class PackedUnihex {

    private static final int BLOCK_BITS = 8;
    private static final int BLOCK_SIZE = 1 << BLOCK_BITS;
    private static final int BLOCKS = 0x110000 >> BLOCK_BITS;
    private static final int LINES = 16;

    private static boolean enabled;

    /** Per block of 256 codepoints, each glyph's first byte in {@link #bytes} (-1: none), or null. */
    private final int[][] offsets = new int[BLOCKS][];
    /** Per block, each glyph's width in bits (8, 16, 24 or 32), left edge and right edge. */
    private final byte[][] widths = new byte[BLOCKS][];

    private final byte[][] lefts = new byte[BLOCKS][];
    private final byte[][] rights = new byte[BLOCKS][];
    private byte[] bytes = new byte[1 << 20];
    private int size;
    private final IntSet codepoints = new IntOpenHashSet();

    private PackedUnihex() {}

    /** Off unless the page turns it on ({@code ?unihex=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Makes a glyph from its lines and edges: the game's glyph record's constructor. */
    @FunctionalInterface
    public interface GlyphFactory<G extends UnbakedGlyph> {
        G make(UnihexProvider.LineData lines, int left, int right);
    }

    /**
     * Hook ({@code UnihexProvider.Definition.loadData}): the font's glyphs packed, read through the
     * game's record's accessors.
     */
    public static <G> PackedUnihex pack(
            CodepointMap<G> glyphs,
            Function<G, UnihexProvider.LineData> lines,
            ToIntFunction<G> left,
            ToIntFunction<G> right) {
        PackedUnihex packed = new PackedUnihex();
        glyphs.forEach((codepoint, glyph) ->
                packed.add(codepoint, lines.apply(glyph), left.applyAsInt(glyph), right.applyAsInt(glyph)));
        packed.bytes = Arrays.copyOf(packed.bytes, packed.size);
        return packed;
    }

    private void add(int codepoint, UnihexProvider.LineData lines, int left, int right) {
        int width = lines.bitWidth();
        if (width != 8 && width != 16 && width != 24 && width != 32) {
            throw new IllegalArgumentException("A unihex glyph " + width + " bits wide, at " + codepoint);
        }
        int block = codepoint >> BLOCK_BITS;
        int[] blockOffsets = offsets[block];
        if (blockOffsets == null) {
            blockOffsets = new int[BLOCK_SIZE];
            Arrays.fill(blockOffsets, -1);
            offsets[block] = blockOffsets;
            widths[block] = new byte[BLOCK_SIZE];
            lefts[block] = new byte[BLOCK_SIZE];
            rights[block] = new byte[BLOCK_SIZE];
        }
        int index = codepoint & (BLOCK_SIZE - 1);
        int lineBytes = width / 8;
        int need = LINES * lineBytes;
        if (size + need > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + need));
        }
        blockOffsets[index] = size;
        for (int i = 0; i < LINES; i++) {
            // The line's bits sit at the top of the int; keep only those.
            int bits = lines.line(i) >>> (32 - width);
            for (int b = lineBytes - 1; b >= 0; b--) {
                bytes[size++] = (byte) (bits >>> (8 * b));
            }
        }
        widths[block][index] = (byte) width;
        lefts[block][index] = (byte) left;
        rights[block][index] = (byte) right;
        codepoints.add(codepoint);
    }

    /** Hook ({@code UnihexProvider.getGlyph}): the glyph at {@code codepoint}, made anew, or null. */
    public <G extends UnbakedGlyph> @Nullable G get(int codepoint, GlyphFactory<G> factory) {
        if (codepoint < 0 || codepoint >= BLOCKS << BLOCK_BITS) {
            return null;
        }
        int block = codepoint >> BLOCK_BITS;
        int[] blockOffsets = offsets[block];
        if (blockOffsets == null) {
            return null;
        }
        int index = codepoint & (BLOCK_SIZE - 1);
        int offset = blockOffsets[index];
        if (offset < 0) {
            return null;
        }
        return factory.make(new Lines(bytes, offset, widths[block][index]), lefts[block][index], rights[block][index]);
    }

    /** Hook ({@code UnihexProvider.getSupportedGlyphs}): every codepoint with a glyph. */
    public IntSet codepoints() {
        return codepoints;
    }

    /** A glyph's lines, read from the shared bytes. */
    private static final class Lines implements UnihexProvider.LineData {
        private final byte[] bytes;
        private final int offset;
        private final int width;

        Lines(byte[] bytes, int offset, int width) {
            this.bytes = bytes;
            this.offset = offset;
            this.width = width;
        }

        @Override
        public int line(int index) {
            int lineBytes = width / 8;
            int at = offset + index * lineBytes;
            int bits = 0;
            for (int b = 0; b < lineBytes; b++) {
                bits = bits << 8 | (bytes[at + b] & 0xFF);
            }
            return bits << (32 - width);
        }

        @Override
        public int bitWidth() {
            return width;
        }
    }
}
