package incari.mesher;

/**
 * A block's fluid surfaces, as the game draws them: a top sloped by the heights of the fluid around
 * it and textured along its flow, a bottom, and sides against whatever isn't the same fluid, each
 * hidden where a neighbor's shape covers it.
 *
 * <p>Worker-side, no game objects; part of {@link SectionMesher}, whose snapshot it reads.
 */
final class FluidMesher {

    /** The height of a fluid block that isn't full: what its own height is when it's a source. */
    private static final float SOURCE_HEIGHT = 0.8888889F;
    /** How far a fluid's faces are pulled in from its block's faces, against z-fighting. */
    private static final float INSET = 0.001F;

    private final MeshTables t;
    private final SectionMesher m;
    private float weightedSum;
    private float weightTotal;

    FluidMesher(MeshTables tables, SectionMesher mesher) {
        t = tables;
        m = mesher;
    }

    void mesh(int x, int y, int z, int index, int state) {
        Snapshot s = m.snapshot();
        char[] states = s.states;
        int group = t.fluidGroup[state];
        int down = index + m.step(Dirs.DOWN);
        int up = index + m.step(Dirs.UP);
        int north = index + m.step(Dirs.NORTH);
        int south = index + m.step(Dirs.SOUTH);
        int west = index + m.step(Dirs.WEST);
        int east = index + m.step(Dirs.EAST);
        int downState = states[down];
        int upState = states[up];
        int northState = states[north];
        int southState = states[south];
        int westState = states[west];
        int eastState = states[east];

        boolean showUp = t.fluidGroup[upState] != group;
        boolean showDown =
                sideShows(state, Dirs.DOWN, downState, group) && !covered(Dirs.DOWN, SOURCE_HEIGHT, downState);
        boolean showNorth = sideShows(state, Dirs.NORTH, northState, group);
        boolean showSouth = sideShows(state, Dirs.SOUTH, southState, group);
        boolean showWest = sideShows(state, Dirs.WEST, westState, group);
        boolean showEast = sideShows(state, Dirs.EAST, eastState, group);
        if (!(showUp || showDown || showEast || showWest || showNorth || showSouth)) {
            return;
        }

        int model = t.fluidModel[state];
        int layer = t.fluidModelLayer[model];
        int flags = t.fluidModelFlags[model];
        int blockIndex = Snapshot.blockIndex(x, y, z);
        int tint = (flags & MeshTables.FLUID_MODEL_TINTED) != 0 ? m.fluidTint(blockIndex) : -1;
        float[] light = s.cardinalLighting;

        float self = height(group, index, state);
        float northEast;
        float northWest;
        float southEast;
        float southWest;
        if (self >= 1.0F) {
            northEast = 1.0F;
            northWest = 1.0F;
            southEast = 1.0F;
            southWest = 1.0F;
        } else {
            float heightNorth = height(group, north, northState);
            float heightSouth = height(group, south, southState);
            float heightEast = height(group, east, eastState);
            float heightWest = height(group, west, westState);
            northEast = cornerHeight(group, self, heightNorth, heightEast, north + m.step(Dirs.EAST));
            northWest = cornerHeight(group, self, heightNorth, heightWest, north + m.step(Dirs.WEST));
            southEast = cornerHeight(group, self, heightSouth, heightEast, south + m.step(Dirs.EAST));
            southWest = cornerHeight(group, self, heightSouth, heightWest, south + m.step(Dirs.WEST));
        }

        float fx = x;
        float fy = y;
        float fz = z;
        float bottom = showDown ? INSET : 0.0F;
        int sprites = model * 12;
        float[] sprite = t.fluidSprites;
        // Which of the fluid's animated sprites the section draws: all three, whichever faces show
        // (ADR 0030; one more marked than drawn only animates a sprite nobody sees).
        for (int i = 0; i < 3; i++) {
            int animated = t.fluidAnimated[model * 3 + i];
            if (animated != 0) {
                m.markAnimated(animated - 1);
            }
        }

        if (showUp
                && !covered(
                        Dirs.UP, Math.min(Math.min(northWest, southWest), Math.min(southEast, northEast)), upState)) {
            northWest -= INSET;
            southWest -= INSET;
            southEast -= INSET;
            northEast -= INSET;
            double[] flow = flow(index, state, group);
            float u00;
            float u01;
            float u10;
            float u11;
            float v00;
            float v01;
            float v10;
            float v11;
            if (flow[0] == 0.0 && flow[2] == 0.0) {
                float u0 = sprite[sprites];
                float u1 = sprite[sprites + 1];
                float v0 = sprite[sprites + 2];
                float v1 = sprite[sprites + 3];
                u00 = u0;
                v00 = v0;
                u01 = u0;
                v01 = v1;
                u10 = u1;
                v10 = v1;
                u11 = u1;
                v11 = v0;
            } else {
                float angle = (float) GameMath.atan2(flow[2], flow[0]) - (float) (Math.PI / 2);
                float sin = GameMath.sin(angle) * 0.25F;
                float cos = GameMath.cos(angle) * 0.25F;
                int flowing = sprites + 4;
                u00 = spriteU(sprite, flowing, 0.5F + (-cos - sin));
                v00 = spriteV(sprite, flowing, 0.5F + (-cos + sin));
                u01 = spriteU(sprite, flowing, 0.5F + (-cos + sin));
                v01 = spriteV(sprite, flowing, 0.5F + (cos + sin));
                u10 = spriteU(sprite, flowing, 0.5F + (cos + sin));
                v10 = spriteV(sprite, flowing, 0.5F + (cos - sin));
                u11 = spriteU(sprite, flowing, 0.5F + (cos - sin));
                v11 = spriteV(sprite, flowing, 0.5F + (-cos - sin));
            }
            int topLight = brighter(index, up);
            int topColor = SectionMesher.scale(tint, light[Dirs.UP]);
            face(
                    layer,
                    fx,
                    fy + northWest,
                    fz,
                    u00,
                    v00,
                    fx,
                    fy + southWest,
                    fz + 1.0F,
                    u01,
                    v01,
                    fx + 1.0F,
                    fy + southEast,
                    fz + 1.0F,
                    u10,
                    v10,
                    fx + 1.0F,
                    fy + northEast,
                    fz,
                    u11,
                    v11,
                    topColor,
                    topLight,
                    backwardUpFace(up, group));
        }

        if (showDown) {
            float u0 = sprite[sprites];
            float u1 = sprite[sprites + 1];
            float v0 = sprite[sprites + 2];
            float v1 = sprite[sprites + 3];
            int downLight = brighter(down, index);
            int downColor = SectionMesher.scale(tint, light[Dirs.DOWN]);
            face(
                    layer,
                    fx,
                    fy + bottom,
                    fz,
                    u0,
                    v0,
                    fx + 1.0F,
                    fy + bottom,
                    fz,
                    u1,
                    v0,
                    fx + 1.0F,
                    fy + bottom,
                    fz + 1.0F,
                    u1,
                    v1,
                    fx,
                    fy + bottom,
                    fz + 1.0F,
                    u0,
                    v1,
                    downColor,
                    downLight,
                    false);
        }

        int sideLight = brighter(index, up);
        for (int direction : Dirs.HORIZONTAL) {
            float h0;
            float h1;
            float x0;
            float z0;
            float x1;
            float z1;
            boolean show;
            int faceState;
            switch (direction) {
                case Dirs.NORTH -> {
                    h0 = northWest;
                    h1 = northEast;
                    x0 = fx;
                    x1 = fx + 1.0F;
                    z0 = fz + INSET;
                    z1 = fz + INSET;
                    show = showNorth;
                    faceState = northState;
                }
                case Dirs.SOUTH -> {
                    h0 = southEast;
                    h1 = southWest;
                    x0 = fx + 1.0F;
                    x1 = fx;
                    z0 = fz + 1.0F - INSET;
                    z1 = fz + 1.0F - INSET;
                    show = showSouth;
                    faceState = southState;
                }
                case Dirs.WEST -> {
                    h0 = southWest;
                    h1 = northWest;
                    x0 = fx + INSET;
                    x1 = fx + INSET;
                    z0 = fz + 1.0F;
                    z1 = fz;
                    show = showWest;
                    faceState = westState;
                }
                default -> {
                    h0 = northEast;
                    h1 = southEast;
                    x0 = fx + 1.0F - INSET;
                    x1 = fx + 1.0F - INSET;
                    z0 = fz;
                    z1 = fz + 1.0F;
                    show = showEast;
                    faceState = eastState;
                }
            }
            if (show && !covered(direction, Math.max(h0, h1), faceState)) {
                int at = sprites + 4;
                boolean overlay = false;
                if ((flags & MeshTables.FLUID_MODEL_OVERLAY) != 0
                        && (t.stateFlags[faceState] & StateFlags.FLUID_OVERLAY) != 0) {
                    at = sprites + 8;
                    overlay = true;
                }
                float u0 = spriteU(sprite, at, 0.0F);
                float u1 = spriteU(sprite, at, 0.5F);
                float v01 = spriteV(sprite, at, (1.0F - h0) * 0.5F);
                float v02 = spriteV(sprite, at, (1.0F - h1) * 0.5F);
                float v1 = spriteV(sprite, at, 0.5F);
                float sideShade = Dirs.axisXyz(direction) == 2 ? light[Dirs.NORTH] : light[Dirs.WEST];
                int color = SectionMesher.scale(tint, light[Dirs.UP] * sideShade);
                face(
                        layer,
                        x0,
                        fy + h0,
                        z0,
                        u0,
                        v01,
                        x1,
                        fy + h1,
                        z1,
                        u1,
                        v02,
                        x1,
                        fy + bottom,
                        z1,
                        u1,
                        v1,
                        x0,
                        fy + bottom,
                        z0,
                        u0,
                        v1,
                        color,
                        sideLight,
                        !overlay);
            }
        }
    }

    /** A face toward {@code direction} shows: the neighbor isn't the same fluid, nor hidden by the block. */
    private boolean sideShows(int state, int direction, int neighborState, int group) {
        return t.fluidGroup[neighborState] != group
                && (t.stateFlags[state] >> StateFlags.HIDES_OWN_FLUID_SHIFT & 1 << direction) == 0;
    }

    /** Whether a neighbor's shape covers a fluid face toward it, the fluid being {@code height} high. */
    private boolean covered(int direction, float height, int neighborState) {
        int at = neighborState * 6 + direction;
        return switch (t.fluidCoverKind[at]) {
            case MeshTables.FLUID_COVER_NONE -> false;
            case MeshTables.FLUID_COVER_FULL_BLOCK -> direction != Dirs.UP || height == 1.0F;
            default -> {
                float cover = t.fluidCoverHeight[at];
                yield switch (direction) {
                    case Dirs.UP -> cover > 0.0F && Math.abs(height - 1.0) <= 1.0E-7;
                    case Dirs.DOWN -> cover > 0.0F;
                    default -> height <= cover;
                };
            }
        };
    }

    /** The fluid's height at a neighbor: 1 under the same fluid, its own if the same, else 0 or -1. */
    private float height(int group, int index, int state) {
        if (t.fluidGroup[state] == group) {
            return t.fluidGroup[m.snapshot().states[index + m.step(Dirs.UP)]] == group ? 1.0F : t.fluidHeight[state];
        }
        return (t.stateFlags[state] & StateFlags.LEGACY_SOLID) == 0 ? 0.0F : -1.0F;
    }

    /** A top corner's height: the fluid's and its neighbors', solid ones counting ten times over. */
    private float cornerHeight(int group, float self, float height2, float height1, int corner) {
        if (height1 >= 1.0F || height2 >= 1.0F) {
            return 1.0F;
        }
        weightedSum = 0.0F;
        weightTotal = 0.0F;
        if (height1 > 0.0F || height2 > 0.0F) {
            float cornerHeight = height(group, corner, m.snapshot().states[corner]);
            if (cornerHeight >= 1.0F) {
                return 1.0F;
            }
            addHeight(cornerHeight);
        }
        addHeight(self);
        addHeight(height1);
        addHeight(height2);
        return weightedSum / weightTotal;
    }

    private void addHeight(float height) {
        if (height >= 0.8F) {
            weightedSum += height * 10.0F;
            weightTotal += 10.0F;
        } else if (height >= 0.0F) {
            weightedSum += height;
            weightTotal++;
        }
    }

    private final double[] flow = new double[3];

    /** The direction the fluid flows in, normalized: down the slope, and down a falling fluid's walls. */
    private double[] flow(int index, int state, int group) {
        char[] states = m.snapshot().states;
        double flowX = 0.0;
        double flowZ = 0.0;
        float own = t.fluidHeight[state];
        for (int direction : Dirs.HORIZONTAL) {
            int neighbor = index + m.step(direction);
            int neighborState = states[neighbor];
            if (affectsFlow(neighborState, group)) {
                float neighborHeight = t.fluidHeight[neighborState];
                float distance = 0.0F;
                if (neighborHeight == 0.0F) {
                    if ((t.stateFlags[neighborState] & StateFlags.BLOCKS_FLUID_FLOW) == 0) {
                        int below = states[neighbor + m.step(Dirs.DOWN)];
                        if (affectsFlow(below, group)) {
                            neighborHeight = t.fluidHeight[below];
                            if (neighborHeight > 0.0F) {
                                distance = own - (neighborHeight - SOURCE_HEIGHT);
                            }
                        }
                    }
                } else if (neighborHeight > 0.0F) {
                    distance = own - neighborHeight;
                }
                if (distance != 0.0F) {
                    flowX += Dirs.STEP_X[direction] * distance;
                    flowZ += Dirs.STEP_Z[direction] * distance;
                }
            }
        }
        flow[0] = flowX;
        flow[1] = 0.0;
        flow[2] = flowZ;
        if ((t.stateFlags[state] & StateFlags.FLUID_FALLING) != 0) {
            for (int direction : Dirs.HORIZONTAL) {
                int neighbor = index + m.step(direction);
                if (solidFace(neighbor, direction, group) || solidFace(neighbor + m.step(Dirs.UP), direction, group)) {
                    normalize(flow);
                    flow[0] += 0.0;
                    flow[1] += -6.0;
                    flow[2] += 0.0;
                    break;
                }
            }
        }
        normalize(flow);
        return flow;
    }

    private static void normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length < 1.0E-5F) {
            v[0] = 0.0;
            v[1] = 0.0;
            v[2] = 0.0;
        } else {
            v[0] /= length;
            v[1] /= length;
            v[2] /= length;
        }
    }

    /** A neighbor takes part in the flow if it holds no fluid or the same fluid. */
    private boolean affectsFlow(int neighborState, int group) {
        int neighborGroup = t.fluidGroup[neighborState];
        return neighborGroup == 0 || neighborGroup == group;
    }

    private boolean solidFace(int index, int direction, int group) {
        int state = m.snapshot().states[index];
        if (t.fluidGroup[state] == group) {
            return false;
        }
        if (direction == Dirs.UP) {
            return true;
        }
        if ((t.stateFlags[state] & StateFlags.ICE) != 0) {
            return false;
        }
        return m.sturdy(state, index, direction);
    }

    /** Whether the top shows from below too: some block around above lets the surface be seen. */
    private boolean backwardUpFace(int above, int group) {
        char[] states = m.snapshot().states;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int state = states[above + ox * Snapshot.STEP_X + oz * Snapshot.STEP_Z];
                if (t.fluidGroup[state] != group && (t.stateFlags[state] & StateFlags.SOLID_RENDER) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The brighter, for block and sky light each, of two positions' light. */
    private int brighter(int a, int b) {
        char[] states = m.snapshot().states;
        return AmbientOcclusion.max(m.lightCoords(states[a], a), m.lightCoords(states[b], b));
    }

    private static float spriteU(float[] sprites, int at, float offset) {
        float u0 = sprites[at];
        return u0 + (sprites[at + 1] - u0) * offset;
    }

    private static float spriteV(float[] sprites, int at, float offset) {
        float v0 = sprites[at + 2];
        return v0 + (sprites[at + 3] - v0) * offset;
    }

    private void face(
            int layer,
            float x0,
            float y0,
            float z0,
            float u0,
            float v0,
            float x1,
            float y1,
            float z1,
            float u1,
            float v1,
            float x2,
            float y2,
            float z2,
            float u2,
            float v2,
            float x3,
            float y3,
            float z3,
            float u3,
            float v3,
            int color,
            int light,
            boolean backFace) {
        m.vertex(layer, x0, y0, z0, color, u0, v0, light);
        m.vertex(layer, x1, y1, z1, color, u1, v1, light);
        m.vertex(layer, x2, y2, z2, color, u2, v2, light);
        m.vertex(layer, x3, y3, z3, color, u3, v3, light);
        if (backFace) {
            m.vertex(layer, x0, y0, z0, color, u0, v0, light);
            m.vertex(layer, x3, y3, z3, color, u3, v3, light);
            m.vertex(layer, x2, y2, z2, color, u2, v2, light);
            m.vertex(layer, x1, y1, z1, color, u1, v1, light);
        }
    }
}
