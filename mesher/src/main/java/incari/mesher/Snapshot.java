package incari.mesher;

import java.util.Arrays;

/**
 * One section to mesh, flattened: the block states and light of the section and two blocks around
 * it (smooth lighting looks that far), the colors its tinted blocks take where they stand, and the
 * few per-position facts that don't follow from a state. The game's side fills it from its copy of
 * the section's surroundings; the mesher reads nothing else of the world.
 *
 * <p>Positions are section-relative, from -2 to 17 on each axis: {@link #index}.
 */
public final class Snapshot {

    /** The border around the section, in blocks. */
    public static final int BORDER = 2;
    /** The snapshot's edge: the section's 16 blocks and the border on both sides. */
    public static final int SIZE = 16 + 2 * BORDER;

    public static final int VOLUME = SIZE * SIZE * SIZE;
    static final int STEP_X = 1;
    static final int STEP_Z = SIZE;
    static final int STEP_Y = SIZE * SIZE;

    /** Vertex formats: the game's own block format (28 bytes), or Incari's compact one (16). */
    public static final int FORMAT_VANILLA = 0;

    public static final int FORMAT_COMPACT = 1;

    /** Where a section-relative position is in {@link #states} and {@link #light}. */
    public static int index(int x, int y, int z) {
        return ((y + BORDER) * SIZE + (z + BORDER)) * SIZE + (x + BORDER);
    }

    /** A position in the section itself, as the mesher numbers its blocks: y, then z, then x. */
    public static int blockIndex(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    /** The section's lowest corner, in block coordinates. */
    public int originX;

    public int originY;
    public int originZ;
    /** The level's shading of each face direction ({@code CardinalLighting}). */
    public final float[] cardinalLighting = new float[6];

    /** Block state ids. */
    public final char[] states = new char[VOLUME];
    /** Sky light in the high nibble, block light in the low one. */
    public final byte[] light = new byte[VOLUME];

    /** The camera relative to the section's origin, for sorting translucent quads back to front. */
    public float cameraX;

    public float cameraY;
    public float cameraZ;
    /** {@link #FORMAT_VANILLA} or {@link #FORMAT_COMPACT}. */
    public int vertexFormat;
    /**
     * Whether a translucent layer may use its {@link FixedOrder} instead of being sorted from
     * {@link #cameraX}.
     */
    public boolean fixedOrder;

    // Tints: for each tinted block in the section, its model's colors (one per tint source) and,
    // for a fluid, the fluid's color.
    int tintCount;
    short[] tintBlock = new short[256];
    byte[] tintModelCount = new byte[256];
    boolean[] tintHasFluid = new boolean[256];
    int[] tintStart = new int[256];
    int[] tintValues = new int[512];
    int tintValueCount;

    // Positions whose state has a shape that depends on where it is (StateFlags.DYNAMIC_SHAPE).
    int dynamicCount;
    short[] dynamicIndex = new short[16];
    byte[] dynamicBits = new byte[16];
    float[] dynamicShade = new float[16];

    static final int DYNAMIC_COLLISION_FULL = 1;
    static final int DYNAMIC_STURDY_SHIFT = 1;

    // Quads the game's side made already, lit and textured, written into the cutout layer as they
    // are: block entities drawn as part of the section (ADR 0030).
    int bakedCount;
    float[] bakedPositions = new float[12 * 16];
    float[] bakedUvs = new float[8 * 16];
    int[] bakedColors = new int[4 * 16];
    int[] bakedLight = new int[16];
    private final float[] scratchPositions = new float[12];
    private final float[] scratchUvs = new float[8];
    private final int[] scratchColors = new int[4];

    /** Empties the per-block lists before the snapshot is filled again. */
    public void clearLists() {
        tintCount = 0;
        tintValueCount = 0;
        dynamicCount = 0;
        bakedCount = 0;
    }

    /**
     * Adds a finished quad to the cutout layer: four corners' section-relative positions (x y z each)
     * and texture coordinates (u v each), their ARGB colors, and the packed light of all four.
     */
    public void addBakedQuad(float[] positions, float[] uvs, int[] colors, int light) {
        if (bakedCount == bakedLight.length) {
            int grown = bakedCount * 2;
            bakedPositions = Arrays.copyOf(bakedPositions, grown * 12);
            bakedUvs = Arrays.copyOf(bakedUvs, grown * 8);
            bakedColors = Arrays.copyOf(bakedColors, grown * 4);
            bakedLight = Arrays.copyOf(bakedLight, grown);
        }
        System.arraycopy(positions, 0, bakedPositions, bakedCount * 12, 12);
        System.arraycopy(uvs, 0, bakedUvs, bakedCount * 8, 8);
        System.arraycopy(colors, 0, bakedColors, bakedCount * 4, 4);
        bakedLight[bakedCount] = light;
        bakedCount++;
    }

    public int bakedCount() {
        return bakedCount;
    }

    /**
     * The colors a tinted block takes: {@code modelTints[0..modelCount)} by tint index, and the
     * fluid's color if it has a fluid ({@code fluidTint} is ignored otherwise). Blocks are added in
     * any order, each once.
     */
    public void addTint(int blockIndex, int[] modelTints, int modelCount, boolean hasFluid, int fluidTint) {
        if (tintCount == tintBlock.length) {
            int grown = tintCount * 2;
            tintBlock = Arrays.copyOf(tintBlock, grown);
            tintModelCount = Arrays.copyOf(tintModelCount, grown);
            tintHasFluid = Arrays.copyOf(tintHasFluid, grown);
            tintStart = Arrays.copyOf(tintStart, grown);
        }
        int needed = modelCount + 1;
        if (tintValueCount + needed > tintValues.length) {
            tintValues = Arrays.copyOf(tintValues, Math.max(tintValueCount + needed, tintValues.length * 2));
        }
        tintBlock[tintCount] = (short) blockIndex;
        tintModelCount[tintCount] = (byte) modelCount;
        tintHasFluid[tintCount] = hasFluid;
        tintStart[tintCount] = tintValueCount;
        System.arraycopy(modelTints, 0, tintValues, tintValueCount, modelCount);
        tintValues[tintValueCount + modelCount] = fluidTint;
        tintValueCount += needed;
        tintCount++;
    }

    /**
     * What a dynamic-shape state is at {@code index}: whether its collision shape is the full block,
     * which faces are sturdy (bit per direction), and its shade.
     */
    public void addDynamic(int index, boolean collisionFull, int sturdyFaces, float shade) {
        if (dynamicCount == dynamicIndex.length) {
            int grown = dynamicCount * 2;
            dynamicIndex = Arrays.copyOf(dynamicIndex, grown);
            dynamicBits = Arrays.copyOf(dynamicBits, grown);
            dynamicShade = Arrays.copyOf(dynamicShade, grown);
        }
        dynamicIndex[dynamicCount] = (short) index;
        dynamicBits[dynamicCount] =
                (byte) ((collisionFull ? DYNAMIC_COLLISION_FULL : 0) | sturdyFaces << DYNAMIC_STURDY_SHIFT);
        dynamicShade[dynamicCount] = shade;
        dynamicCount++;
    }

    public int dynamicCount() {
        return dynamicCount;
    }

    public void encode(Bytes.Out out) {
        out.putInt(originX);
        out.putInt(originY);
        out.putInt(originZ);
        for (float f : cardinalLighting) {
            out.putFloat(f);
        }
        out.putFloat(cameraX);
        out.putFloat(cameraY);
        out.putFloat(cameraZ);
        out.putByte(vertexFormat);
        out.putByte(fixedOrder ? 1 : 0);
        out.putChars(states, VOLUME);
        out.putByteArray(light, VOLUME);
        out.putInt(tintCount);
        for (int i = 0; i < tintCount; i++) {
            out.putShort(tintBlock[i]);
            out.putByte(tintModelCount[i]);
            out.putByte(tintHasFluid[i] ? 1 : 0);
            int start = tintStart[i];
            for (int k = 0; k <= tintModelCount[i]; k++) {
                out.putInt(tintValues[start + k]);
            }
        }
        out.putInt(dynamicCount);
        for (int i = 0; i < dynamicCount; i++) {
            out.putShort(dynamicIndex[i]);
            out.putByte(dynamicBits[i]);
            out.putFloat(dynamicShade[i]);
        }
        out.putInt(bakedCount);
        out.putFloats(bakedPositions, bakedCount * 12);
        out.putFloats(bakedUvs, bakedCount * 8);
        out.putInts(bakedColors, bakedCount * 4);
        out.putInts(bakedLight, bakedCount);
    }

    /** Fills this snapshot from what {@link #encode} wrote. */
    public void decode(Bytes.In in) {
        clearLists();
        originX = in.getInt();
        originY = in.getInt();
        originZ = in.getInt();
        for (int i = 0; i < 6; i++) {
            cardinalLighting[i] = in.getFloat();
        }
        cameraX = in.getFloat();
        cameraY = in.getFloat();
        cameraZ = in.getFloat();
        vertexFormat = in.getByte();
        fixedOrder = in.getByte() != 0;
        in.getChars(states);
        in.getByteArray(light);
        int tints = in.getInt();
        int[] scratch = new int[8];
        for (int i = 0; i < tints; i++) {
            int block = in.getShort() & 0xFFFF;
            int modelCount = in.getUnsignedByte();
            boolean hasFluid = in.getByte() != 0;
            if (scratch.length < modelCount + 1) {
                scratch = new int[modelCount + 1];
            }
            for (int k = 0; k <= modelCount; k++) {
                scratch[k] = in.getInt();
            }
            addTint(block, scratch, modelCount, hasFluid, scratch[modelCount]);
        }
        int dynamics = in.getInt();
        for (int i = 0; i < dynamics; i++) {
            int index = in.getShort() & 0xFFFF;
            int bits = in.getUnsignedByte();
            float shade = in.getFloat();
            addDynamic(index, (bits & DYNAMIC_COLLISION_FULL) != 0, bits >> DYNAMIC_STURDY_SHIFT & 0x3F, shade);
        }
        int baked = in.getInt();
        float[] positions = in.getFloats();
        float[] uvs = in.getFloats();
        int[] colors = in.getInts();
        int[] light = in.getInts();
        bakedCount = 0;
        for (int q = 0; q < baked; q++) {
            System.arraycopy(positions, q * 12, scratchPositions, 0, 12);
            System.arraycopy(uvs, q * 8, scratchUvs, 0, 8);
            System.arraycopy(colors, q * 4, scratchColors, 0, 4);
            addBakedQuad(scratchPositions, scratchUvs, scratchColors, light[q]);
        }
    }
}
