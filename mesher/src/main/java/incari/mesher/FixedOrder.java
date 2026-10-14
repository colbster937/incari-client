package incari.mesher;

/**
 * A translucent layer's order that holds from every camera, so it never needs sorting again
 * (Sodium's static translucency sorting, written fresh).
 *
 * <p>The game sorts a section's translucent quads back to front by their centers, again whenever
 * the camera crosses a block near the section or moves to another side of it. But when every quad
 * lies flat across one axis (the still water of a lake or the sea: its surface, and the surface
 * seen from below), one order is right for any camera, because the terrain pipelines cull back
 * faces:
 *
 * <ul>
 *   <li>A quad facing +axis is drawn only while the camera is on its + side, and a ray from the
 *       camera meets the lower of two such planes later: it's farther, and drawn first. So quads
 *       facing +axis go in rising order of their plane, and quads facing -axis in falling order.
 *   <li>A quad facing +axis that's drawn lies below the camera's plane across the axis, and one
 *       facing -axis above it, so the two never cover each other on screen: how they interleave
 *       doesn't matter.
 *   <li>Quads in one plane facing one way don't cover each other (blocks don't overlap), so they
 *       keep the order they were written in.
 * </ul>
 *
 * <p>The game's order by centers can differ only where quads don't cover each other on screen (so
 * order doesn't matter), or where centers put a nearer overlapping quad first, which this order
 * gets right.
 *
 * <p>Worker-side, no game objects.
 */
final class FixedOrder {

    private FixedOrder() {}

    /**
     * Whether {@code count} quads of these facings ({@link Facings}) all lie across one axis: then
     * {@link #keys} gives their order.
     */
    static boolean holds(byte[] facings, int count) {
        if (count == 0) {
            return false;
        }
        int axis = -1;
        for (int q = 0; q < count; q++) {
            int facing = facings[q];
            if (facing == Facings.UNASSIGNED) {
                return false;
            }
            if (axis == -1) {
                axis = facing >> 1;
            } else if (facing >> 1 != axis) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes each quad's sort key into {@code keys}, larger first ({@link QuadSorter}'s rule): a quad
     * facing +axis by its plane rising, one facing -axis by its plane falling.
     */
    static void keys(byte[] facings, float[] planes, int count, float[] keys) {
        for (int q = 0; q < count; q++) {
            // Up, south and east (odd facings) face +axis.
            keys[q] = (facings[q] & 1) != 0 ? -planes[q] : planes[q];
        }
    }
}
