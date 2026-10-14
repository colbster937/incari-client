package incari.mesher;

/**
 * Which way a quad faces, for drawing only the quads a camera can see the front of (ADR 0030).
 *
 * <p>A quad whose corners lie in one plane across an axis faces along that axis, toward its front
 * (counter-clockwise winding, as the terrain pipelines cull). The solid and cutout layers group
 * their quads by facing ({@link #runOf}'s order), so the quads facing away from the camera are one
 * range the draw can skip; the GPU would cull them all anyway. Anything else (tilted quads, crossed
 * plants, sloped fluid, planes far outside the section) is {@link #UNASSIGNED} and always drawn.
 *
 * <p>Decided from a quad's positions alone, the same way for the game's mesh and Incari's, so the
 * desktop's comparison of the two can group the game's quads as the mesher does.
 */
public final class Facings {

    private Facings() {}

    /** The six directions, in the game's order (down, up, north, south, west, east), then this. */
    public static final int UNASSIGNED = 6;

    public static final int COUNT = 7;

    /**
     * A facing's run: where its quads come in a layer grouped by facing. Unassigned first, then the
     * directions in the game's order.
     */
    public static int runOf(int facing) {
        return facing == UNASSIGNED ? 0 : facing + 1;
    }

    /** The facing of a run: the inverse of {@link #runOf}. */
    public static int facingOfRun(int run) {
        return run == 0 ? UNASSIGNED : run - 1;
    }

    /**
     * How far outside its section, in blocks, a quad's plane may lie and still be assigned a facing.
     * Block models reach at most a block past their own (elements from -16 to 32), and plant
     * offsets a quarter more.
     */
    public static final float PLANE_MARGIN = 2.0F;

    /**
     * How far past {@link #PLANE_MARGIN} the camera must be before a section's away-facing quads
     * are skipped: room for view bobbing (the eye moves off the camera's position) and rounding.
     * Closer than that, a section draws them all.
     */
    public static final float CAMERA_MARGIN = 1.0F;

    /**
     * The facing of the quad with these corners (section-relative block coordinates, in the order
     * they're written: triangles 0 1 2 and 2 3 0).
     */
    @SuppressWarnings("ParameterNumber") // one quad's twelve coordinates, unboxed: a hot path
    public static int of(
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3) {
        boolean flatX = x0 == x1 && x0 == x2 && x0 == x3;
        boolean flatY = y0 == y1 && y0 == y2 && y0 == y3;
        boolean flatZ = z0 == z1 && z0 == z2 && z0 == z3;
        // The normals of both triangles: (p1 - p0) x (p2 - p0) and (p2 - p0) x (p3 - p0).
        float ax = x1 - x0;
        float ay = y1 - y0;
        float az = z1 - z0;
        float bx = x2 - x0;
        float by = y2 - y0;
        float bz = z2 - z0;
        float cx = x3 - x0;
        float cy = y3 - y0;
        float cz = z3 - z0;
        if (flatX && !flatY && !flatZ) {
            return facing(ay * bz - az * by, by * cz - bz * cy, x0, 5, 4);
        }
        if (flatY && !flatX && !flatZ) {
            return facing(az * bx - ax * bz, bz * cx - bx * cz, y0, 1, 0);
        }
        if (flatZ && !flatX && !flatY) {
            return facing(ax * by - ay * bx, bx * cy - by * cx, z0, 3, 2);
        }
        return UNASSIGNED;
    }

    /**
     * The facing of a flat quad from its triangles' normals along its axis: both toward the same
     * side (or one degenerate), and its plane near the section.
     */
    private static int facing(float first, float second, float plane, int positive, int negative) {
        if (plane < -PLANE_MARGIN || plane > 16.0F + PLANE_MARGIN) {
            return UNASSIGNED;
        }
        if (first >= 0.0F && second >= 0.0F && (first > 0.0F || second > 0.0F)) {
            return positive;
        }
        if (first <= 0.0F && second <= 0.0F && (first < 0.0F || second < 0.0F)) {
            return negative;
        }
        return UNASSIGNED;
    }

    /**
     * Which facings a camera at these section-relative coordinates may see the front of, as bits
     * (bit {@code f} for facing {@code f}, {@link #UNASSIGNED} always set).
     */
    public static int visible(float cameraX, float cameraY, float cameraZ) {
        float low = -PLANE_MARGIN - CAMERA_MARGIN;
        float high = 16.0F + PLANE_MARGIN + CAMERA_MARGIN;
        int bits = 1 << UNASSIGNED;
        // A face toward +axis has its front on the +axis side of its plane, which lies at or above
        // -PLANE_MARGIN: from below `low` none of them can be seen.
        bits |= cameraY < high ? 1 : 0; // down
        bits |= cameraY > low ? 1 << 1 : 0; // up
        bits |= cameraZ < high ? 1 << 2 : 0; // north
        bits |= cameraZ > low ? 1 << 3 : 0; // south
        bits |= cameraX < high ? 1 << 4 : 0; // west
        bits |= cameraX > low ? 1 << 5 : 0; // east
        return bits;
    }

    /**
     * The ranges of a layer's quads to draw for a camera that sees {@code facings} (bits from
     * {@link #visible}): its runs ({@code runs[base]} to {@code runs[base + COUNT - 1]}, in
     * {@link #runOf}'s order) whose facing is seen, runs next to each other merged into one range.
     *
     * @param ranges filled with each range's first quad and quad count, in pairs; at least
     *     {@code 2 * COUNT} long
     * @return how many ranges
     */
    public static int visibleRanges(int[] runs, int base, int facings, int[] ranges) {
        int count = 0;
        int quad = 0;
        int start = -1;
        int end = -1;
        for (int run = 0; run < COUNT; run++) {
            int quads = runs[base + run];
            if (quads == 0) {
                continue;
            }
            if ((facings & 1 << facingOfRun(run)) != 0) {
                if (start != -1 && end != quad) {
                    ranges[count * 2] = start;
                    ranges[count * 2 + 1] = end - start;
                    count++;
                    start = -1;
                }
                if (start == -1) {
                    start = quad;
                }
                end = quad + quads;
            }
            quad += quads;
        }
        if (start != -1) {
            ranges[count * 2] = start;
            ranges[count * 2 + 1] = end - start;
            count++;
        }
        return count;
    }
}
