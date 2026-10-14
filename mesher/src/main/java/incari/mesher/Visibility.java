package incari.mesher;

/**
 * Which faces of a section can see each other through it, for the game's occlusion graph: a flood
 * fill of the section's open blocks from its edges, each connected region joining the faces it
 * touches. A section with little in it counts as open everywhere; a full one as closed.
 *
 * <p>Blocks are numbered as the game's visibility graph numbers them: x, then z, then y.
 */
final class Visibility {

    private final boolean[] closed = new boolean[4096];
    private final int[] queue = new int[4096];
    private int opaque;

    void clear() {
        java.util.Arrays.fill(closed, false);
        opaque = 0;
    }

    void setOpaque(int blockIndex) {
        closed[blockIndex] = true;
        opaque++;
    }

    /** The visibility bits: bit {@code a + 6 * b} for faces a and b, both ways. */
    long resolve() {
        if (opaque < 256) {
            return (1L << 36) - 1;
        }
        if (opaque == 4096) {
            return 0L;
        }
        long visible = 0L;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) {
                        continue;
                    }
                    int start = Snapshot.blockIndex(x, y, z);
                    if (!closed[start]) {
                        int faces = fill(start);
                        for (int a = 0; a < 6; a++) {
                            if ((faces & 1 << a) != 0) {
                                for (int b = 0; b < 6; b++) {
                                    if ((faces & 1 << b) != 0) {
                                        visible |= 1L << (a + 6 * b);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return visible;
    }

    /** Fills the open region from {@code start}; returns the faces it touches, a bit per direction. */
    private int fill(int start) {
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        closed[start] = true;
        int faces = 0;
        while (head < tail) {
            int index = queue[head++];
            int x = index & 15;
            int z = index >> 4 & 15;
            int y = index >> 8 & 15;
            if (x == 0) {
                faces |= 1 << Dirs.WEST;
            } else if (x == 15) {
                faces |= 1 << Dirs.EAST;
            }
            if (y == 0) {
                faces |= 1 << Dirs.DOWN;
            } else if (y == 15) {
                faces |= 1 << Dirs.UP;
            }
            if (z == 0) {
                faces |= 1 << Dirs.NORTH;
            } else if (z == 15) {
                faces |= 1 << Dirs.SOUTH;
            }
            if (y > 0) {
                tail = visit(index - 256, tail);
            }
            if (y < 15) {
                tail = visit(index + 256, tail);
            }
            if (z > 0) {
                tail = visit(index - 16, tail);
            }
            if (z < 15) {
                tail = visit(index + 16, tail);
            }
            if (x > 0) {
                tail = visit(index - 1, tail);
            }
            if (x < 15) {
                tail = visit(index + 1, tail);
            }
        }
        return faces;
    }

    private int visit(int index, int tail) {
        if (!closed[index]) {
            closed[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }
}
