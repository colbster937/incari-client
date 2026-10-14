package incari.mesher;

/**
 * The fixed geometry of the game's smooth lighting: for each face direction, the four neighbors
 * around the face that are sampled (in the order the game samples them), which pair of them each of
 * the four blended corner samples combines, and which of the quad's vertices each of the face's
 * corners lights.
 */
final class AmbientOcclusion {

    private AmbientOcclusion() {}

    /** Per face direction, the four directions around it that are sampled. */
    static final int[][] CORNERS = {
        {Dirs.WEST, Dirs.EAST, Dirs.NORTH, Dirs.SOUTH}, // down
        {Dirs.EAST, Dirs.WEST, Dirs.NORTH, Dirs.SOUTH}, // up
        {Dirs.UP, Dirs.DOWN, Dirs.EAST, Dirs.WEST}, // north
        {Dirs.WEST, Dirs.EAST, Dirs.DOWN, Dirs.UP}, // south
        {Dirs.UP, Dirs.DOWN, Dirs.NORTH, Dirs.SOUTH}, // west
        {Dirs.DOWN, Dirs.UP, Dirs.NORTH, Dirs.SOUTH}, // east
    };

    /**
     * The four blended samples, each the pair of {@link #CORNERS} entries whose diagonal it sits on
     * (first the one across the face's second axis, then the first).
     */
    static final int[][] SAMPLE_CORNERS = {{3, 0}, {2, 0}, {2, 1}, {3, 1}};

    /** Per face direction, the quad vertex each of the four face corners (samples) lights. */
    static final int[][] VERTEX_OF_CORNER = {
        {0, 1, 2, 3}, // down
        {2, 3, 0, 1}, // up
        {3, 0, 1, 2}, // north
        {0, 1, 2, 3}, // south
        {3, 0, 1, 2}, // west
        {1, 2, 3, 0}, // east
    };

    /**
     * The game's smooth blend of three neighbors' light with the center's: a dark neighbor
     * (nothing, or no sky light) borrows the center's where the center is lit, then the four are
     * averaged, block and sky light separately.
     */
    static int blend(int neighbor1, int neighbor2, int neighbor3, int center) {
        if (sky(center) > 2 || block(center) > 2) {
            neighbor1 = borrow(neighbor1, center);
            neighbor2 = borrow(neighbor2, center);
            neighbor3 = borrow(neighbor3, center);
        }
        return ((neighbor1 + neighbor2 + neighbor3 + center) >> 2) & 0xFF00FF;
    }

    private static int borrow(int neighbor, int center) {
        if (neighbor == 0) {
            return center;
        }
        return sky(neighbor) == 0 ? neighbor | (center & 0xFF0000) : neighbor;
    }

    /** The four blended corners weighted by where a vertex sits between them. */
    static int weightedBlend(int c1, int c2, int c3, int c4, float w1, float w2, float w3, float w4) {
        int sky = (int)
                ((c1 >> 16 & 0xFF) * w1 + (c2 >> 16 & 0xFF) * w2 + (c3 >> 16 & 0xFF) * w3 + (c4 >> 16 & 0xFF) * w4);
        int block = (int) ((c1 & 0xFF) * w1 + (c2 & 0xFF) * w2 + (c3 & 0xFF) * w3 + (c4 & 0xFF) * w4);
        return (block & 0xFF) | (sky & 0xFF) << 16;
    }

    /** The block light level of packed light coordinates. */
    static int block(int packed) {
        return packed >> 4 & 15;
    }

    /** The sky light level of packed light coordinates. */
    static int sky(int packed) {
        return packed >> 20 & 15;
    }

    static int pack(int block, int sky) {
        return block << 4 | sky << 20;
    }

    /** Light coordinates raised to a quad's own light emission. */
    static int withEmission(int packed, int emission) {
        if (emission == 0) {
            return packed;
        }
        return pack(Math.max(block(packed), emission), Math.max(sky(packed), emission));
    }

    /** Light coordinates with each of block and sky light the brighter of the two. */
    static int max(int a, int b) {
        return pack(Math.max(block(a), block(b)), Math.max(sky(a), sky(b)));
    }
}
