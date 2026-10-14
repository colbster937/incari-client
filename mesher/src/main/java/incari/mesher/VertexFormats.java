package incari.mesher;

/**
 * The two vertex formats the mesher writes. Hot path: one call per vertex, nothing allocated.
 *
 * <ul>
 *   <li>{@link Snapshot#FORMAT_VANILLA}, the game's block format, 28 bytes: position as three
 *       floats, color as RGBA bytes, texture coordinates as two floats, light as two shorts (block,
 *       then sky). Byte for byte what the game's own section compiler writes.
 *   <li>{@link Snapshot#FORMAT_COMPACT}, 16 bytes: position as three unsigned shorts (section
 *       relative, 1/2048 of a block from -8), block and sky light as a byte each, color as RGBA
 *       bytes, texture coordinates as two unsigned shorts (1/32768 of the atlas). The WebGL2
 *       backend decodes it in the terrain shaders (ADR 0023).
 * </ul>
 */
public final class VertexFormats {

    private VertexFormats() {}

    public static final int VANILLA_SIZE = 28;
    public static final int COMPACT_SIZE = 16;

    /** Compact positions: steps a block, and the offset that makes -8 the first. */
    public static final float COMPACT_POSITION_SCALE = 2048.0F;

    public static final float COMPACT_POSITION_OFFSET = 8.0F;
    /** Compact texture coordinates: steps across the atlas. */
    public static final float COMPACT_UV_SCALE = 32768.0F;

    public static int size(int format) {
        return format == Snapshot.FORMAT_COMPACT ? COMPACT_SIZE : VANILLA_SIZE;
    }

    /**
     * Writes one vertex.
     *
     * @param color ARGB
     * @param light packed light coordinates: block in the low 16 bits, sky in the high 16
     */
    static void put(int format, Bytes.Out out, float x, float y, float z, int color, float u, float v, int light) {
        int abgr = (color & 0xFF00FF00) | (color & 0xFF0000) >> 16 | (color & 0xFF) << 16;
        byte[] data;
        if (format == Snapshot.FORMAT_COMPACT) {
            int at = out.reserve(COMPACT_SIZE);
            data = out.array();
            Bytes.putShortAt(data, at, quantize((x + COMPACT_POSITION_OFFSET) * COMPACT_POSITION_SCALE));
            Bytes.putShortAt(data, at + 2, quantize((y + COMPACT_POSITION_OFFSET) * COMPACT_POSITION_SCALE));
            Bytes.putShortAt(data, at + 4, quantize((z + COMPACT_POSITION_OFFSET) * COMPACT_POSITION_SCALE));
            data[at + 6] = (byte) Math.min(light & 0xFFFF, 255);
            data[at + 7] = (byte) Math.min(light >>> 16, 255);
            Bytes.putIntAt(data, at + 8, abgr);
            Bytes.putShortAt(data, at + 12, quantize(u * COMPACT_UV_SCALE));
            Bytes.putShortAt(data, at + 14, quantize(v * COMPACT_UV_SCALE));
        } else {
            int at = out.reserve(VANILLA_SIZE);
            data = out.array();
            Bytes.putFloatAt(data, at, x);
            Bytes.putFloatAt(data, at + 4, y);
            Bytes.putFloatAt(data, at + 8, z);
            Bytes.putIntAt(data, at + 12, abgr);
            Bytes.putFloatAt(data, at + 16, u);
            Bytes.putFloatAt(data, at + 20, v);
            Bytes.putIntAt(data, at + 24, light);
        }
    }

    /** Rounds to the nearest unsigned short, clamped to its range. */
    private static int quantize(float value) {
        int rounded = Math.round(value);
        return rounded < 0 ? 0 : Math.min(rounded, 0xFFFF);
    }
}
