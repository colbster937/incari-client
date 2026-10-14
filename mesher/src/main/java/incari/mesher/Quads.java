package incari.mesher;

/**
 * A model quad's fixed facts, packed in {@link MeshTables#quadInfo}, and what the lighter derives
 * from its shape once, when the tables are built, rather than for every block: its bounds, whether
 * it covers its whole face, and the weights smooth lighting blends its corners with.
 */
final class Quads {

    private Quads() {}

    private static final int DIRECTION_MASK = 0x7;
    private static final int LAYER_SHIFT = 3;
    private static final int TINT_SHIFT = 5;
    private static final int SHADE_SHIFT = 13;
    private static final int EMISSION_SHIFT = 16;
    /** Its four corners lie in one plane across its direction's axis. */
    static final int FLAT = 1 << 20;
    /** It lies on its block's face: at 0 for down, north and west, at 1 for the others. */
    static final int ON_EDGE = 1 << 21;
    /** It doesn't cover its face's whole square. */
    static final int PARTIAL = 1 << 22;

    static int pack(int direction, int layer, int tintIndex, int shadeDirection, int lightEmission) {
        if (tintIndex < -1 || tintIndex > 254) {
            throw new IllegalArgumentException("Tint index out of range: " + tintIndex);
        }
        return direction
                | layer << LAYER_SHIFT
                | (tintIndex + 1) << TINT_SHIFT
                | (shadeDirection + 1) << SHADE_SHIFT
                | lightEmission << EMISSION_SHIFT;
    }

    static int direction(int info) {
        return info & DIRECTION_MASK;
    }

    static int layer(int info) {
        return info >> LAYER_SHIFT & 0x3;
    }

    /** The tint index, or -1 for none. */
    static int tintIndex(int info) {
        return (info >> TINT_SHIFT & 0xFF) - 1;
    }

    /** The direction whose shade the quad takes: its own unless the model overrides it. */
    static int shadeDirection(int info) {
        int override = (info >> SHADE_SHIFT & 0x7) - 1;
        return override >= 0 ? override : direction(info);
    }

    static int lightEmission(int info) {
        return info >> EMISSION_SHIFT & 0xF;
    }

    private static final float EDGE_LOW = 1.0E-4F;
    private static final float EDGE_HIGH = 0.9999F;

    /**
     * Where each of a face's four corners sits in the quad's bounding box, in the lighter's vertex
     * order: for each direction and corner, whether the first and the second axis in the face's
     * plane take the box's maximum (1) or minimum (0). The planes' axes are x and z for down and
     * up, x and y for north and south, y and z for west and east.
     */
    private static final int[][] CORNER_AT_MAX = {
        {0, 1, 0, 0, 1, 0, 1, 1}, // down
        {1, 1, 1, 0, 0, 0, 0, 1}, // up
        {0, 1, 1, 1, 1, 0, 0, 0}, // north
        {0, 1, 0, 0, 1, 0, 1, 1}, // south
        {1, 1, 1, 0, 0, 0, 0, 1}, // west
        {0, 1, 0, 0, 1, 0, 1, 1}, // east
    };

    /** The two in-plane axes of each direction's face, as x = 0, y = 1, z = 2. */
    private static final int[][] PLANE_AXES = {{0, 2}, {0, 2}, {0, 1}, {0, 1}, {1, 2}, {1, 2}};

    static void derive(MeshTables t) {
        float[] positions = t.quadPositions;
        for (int q = 0; q < t.quadInfo.length; q++) {
            // The quad's bounding box, axis by axis: the lower corner, then the upper.
            float[] bounds = t.quadBounds;
            for (int axis = 0; axis < 3; axis++) {
                float low = positions[q * 12 + axis];
                float high = low;
                for (int v = 1; v < 4; v++) {
                    float value = positions[q * 12 + v * 3 + axis];
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
                bounds[q * 6 + axis] = low;
                bounds[q * 6 + 3 + axis] = high;
            }
            float minX = bounds[q * 6];
            float minY = bounds[q * 6 + 1];
            float minZ = bounds[q * 6 + 2];
            float maxX = bounds[q * 6 + 3];
            float maxY = bounds[q * 6 + 4];
            float maxZ = bounds[q * 6 + 5];

            int info = t.quadInfo[q];
            int direction = direction(info);
            boolean partial;
            boolean flat;
            boolean onEdge;
            switch (direction) {
                case Dirs.DOWN, Dirs.UP -> {
                    partial = minX >= EDGE_LOW || minZ >= EDGE_LOW || maxX <= EDGE_HIGH || maxZ <= EDGE_HIGH;
                    flat = minY == maxY;
                    onEdge = direction == Dirs.DOWN ? minY < EDGE_LOW : maxY > EDGE_HIGH;
                }
                case Dirs.NORTH, Dirs.SOUTH -> {
                    partial = minX >= EDGE_LOW || minY >= EDGE_LOW || maxX <= EDGE_HIGH || maxY <= EDGE_HIGH;
                    flat = minZ == maxZ;
                    onEdge = direction == Dirs.NORTH ? minZ < EDGE_LOW : maxZ > EDGE_HIGH;
                }
                default -> {
                    partial = minY >= EDGE_LOW || minZ >= EDGE_LOW || maxY <= EDGE_HIGH || maxZ <= EDGE_HIGH;
                    flat = minX == maxX;
                    onEdge = direction == Dirs.WEST ? minX < EDGE_LOW : maxX > EDGE_HIGH;
                }
            }
            info &= ~(FLAT | ON_EDGE | PARTIAL);
            t.quadInfo[q] = info | (flat ? FLAT : 0) | (onEdge ? ON_EDGE : 0) | (partial ? PARTIAL : 0);

            // Smooth lighting's weights: for each corner of the face, how much of each of the four
            // blended corner samples it takes, by where the corner sits between the samples.
            int[] corners = AmbientOcclusion.CORNERS[direction];
            for (int vertex = 0; vertex < 4; vertex++) {
                for (int sample = 0; sample < 4; sample++) {
                    int towardA = corners[AmbientOcclusion.SAMPLE_CORNERS[sample][0]];
                    int towardB = corners[AmbientOcclusion.SAMPLE_CORNERS[sample][1]];
                    float weight = towards(towardA, direction, vertex, bounds, q)
                            * towards(towardB, direction, vertex, bounds, q);
                    t.quadAoWeights[q * 16 + vertex * 4 + sample] = weight;
                }
            }
        }
    }

    /**
     * How far corner {@code vertex} of the face sits toward {@code toward}: its coordinate on that
     * axis, measured from the far side (1 - coordinate) for the negative directions.
     */
    private static float towards(int toward, int faceDirection, int vertex, float[] bounds, int q) {
        int axis = Dirs.axisXyz(toward);
        int[] plane = PLANE_AXES[faceDirection];
        int planeIndex = plane[0] == axis ? 0 : 1;
        boolean atMax = CORNER_AT_MAX[faceDirection][vertex * 2 + planeIndex] == 1;
        float coordinate = bounds[q * 6 + (atMax ? 3 : 0) + axis];
        return Dirs.positive(toward) ? coordinate : 1.0F - coordinate;
    }
}
