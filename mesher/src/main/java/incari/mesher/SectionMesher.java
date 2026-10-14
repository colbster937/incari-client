package incari.mesher;

import java.util.Arrays;

/**
 * Meshes one section from a {@link Snapshot}: every block's model quads, culled against its
 * neighbors and lit (smoothly or flat, as the game would), its fluid's surfaces, the section's
 * visibility across its faces, and the translucent layer sorted back to front. What it writes is
 * what the game's own section compiler writes for the same blocks, vertex for vertex, in the same
 * order ({@code incari.client.render.mesher.MeshVerifier} checks that on the desktop).
 *
 * <p>Worker-side, no game objects. One instance per thread; it keeps its scratch space between
 * meshes, so after the first few a mesh allocates nothing. Hot path throughout.
 */
public final class SectionMesher {

    private final MeshTables t;
    private final GameMath.Random random = new GameMath.Random();
    private final FluidMesher fluids;
    private final Visibility visibility = new Visibility();
    private final QuadSorter sorter = new QuadSorter();

    private int[] parts = new int[16];
    private int partCount;

    /** Per section block: its entry in the snapshot's tint list, or -1. */
    private final int[] tintEntry = new int[4096];
    /** Per snapshot position: its entry in the snapshot's dynamic-shape list, or -1. */
    private final int[] dynamicEntry = new int[Snapshot.VOLUME];

    /** The quad being written to each layer: its corners so far, x y z each (ADR 0030). */
    private final float[][] corners = new float[MeshTables.LAYER_COUNT][12];
    /** Per layer, each quad's facing as written ({@link Facings}). */
    private final byte[][] quadFacings = {new byte[1024], new byte[256], new byte[256]};
    /** Each translucent quad's plane across its facing's axis, for a {@link FixedOrder}. */
    private float[] translucentPlanes = new float[256];
    /** A layer's vertices while they're regrouped by facing. */
    private byte[] regroup = new byte[16 * 1024];

    private final int[] runStart = new int[Facings.COUNT];

    private final int[] vertexColor = new int[4];
    private final int[] vertexLight = new int[4];
    private final int[] step = new int[6];

    private Snapshot s = new Snapshot();
    private MeshResult out = new MeshResult();
    private int format;
    private float firstX;
    private float firstY;
    private float firstZ;

    public SectionMesher(MeshTables tables) {
        t = tables;
        fluids = new FluidMesher(tables, this);
        Arrays.fill(tintEntry, -1);
        Arrays.fill(dynamicEntry, -1);
        for (int d = 0; d < 6; d++) {
            step[d] = Dirs.STEP_X[d] * Snapshot.STEP_X
                    + Dirs.STEP_Y[d] * Snapshot.STEP_Y
                    + Dirs.STEP_Z[d] * Snapshot.STEP_Z;
        }
    }

    public MeshTables tables() {
        return t;
    }

    /**
     * Meshes {@code snapshot} into {@code result} (cleared first).
     *
     * @return false if the section holds a state the tables couldn't describe; the result is then
     *     incomplete and the game should mesh the section itself
     */
    public boolean mesh(Snapshot snapshot, MeshResult result) {
        s = snapshot;
        out = result;
        format = snapshot.vertexFormat;
        result.clear();
        result.vertexSize = VertexFormats.size(format);
        boolean supported = true;
        prepareLookups();
        try {
            char[] states = s.states;
            int[] flags = t.stateFlags;
            visibility.clear();
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int index = Snapshot.index(x, y, z);
                        int state = states[index];
                        int f = flags[state];
                        if ((f & StateFlags.AIR) != 0) {
                            continue;
                        }
                        if ((f & StateFlags.UNSUPPORTED) != 0) {
                            supported = false;
                        }
                        if ((f & StateFlags.SOLID_RENDER) != 0) {
                            visibility.setOpaque(Snapshot.blockIndex(x, y, z));
                        }
                        if (t.fluidGroup[state] != 0) {
                            fluids.mesh(x, y, z, index, state);
                        }
                        if ((f & StateFlags.MODEL) != 0) {
                            model(x, y, z, index, state, f);
                        }
                    }
                }
            }
            result.visibility = visibility.resolve();
            bakedQuads();
            groupByFacing(MeshTables.LAYER_SOLID);
            groupByFacing(MeshTables.LAYER_CUTOUT);
            result.facingRuns[MeshTables.LAYER_TRANSLUCENT * Facings.COUNT] =
                    result.vertexCounts[MeshTables.LAYER_TRANSLUCENT] / 4;
            if (result.translucentQuads > 0) {
                byte[] facings = quadFacings[MeshTables.LAYER_TRANSLUCENT];
                if (s.fixedOrder && FixedOrder.holds(facings, result.translucentQuads)) {
                    sorter.fixedInto(result, facings, translucentPlanes);
                    result.fixedOrder = true;
                } else {
                    sorter.sortInto(result, s.cameraX, s.cameraY, s.cameraZ);
                }
            }
        } finally {
            clearLookups();
        }
        return supported;
    }

    private void prepareLookups() {
        for (int i = 0; i < s.tintCount; i++) {
            tintEntry[s.tintBlock[i] & 0xFFFF] = i;
        }
        for (int i = 0; i < s.dynamicCount; i++) {
            dynamicEntry[s.dynamicIndex[i] & 0xFFFF] = i;
        }
    }

    private void clearLookups() {
        for (int i = 0; i < s.tintCount; i++) {
            tintEntry[s.tintBlock[i] & 0xFFFF] = -1;
        }
        for (int i = 0; i < s.dynamicCount; i++) {
            dynamicEntry[s.dynamicIndex[i] & 0xFFFF] = -1;
        }
    }

    // ---- What the snapshot says about a position ---------------------------------------------

    /**
     * The game's light coordinates for {@code state} standing at {@code index}: full brightness if
     * it's emissive, else the position's light with block light raised to the state's emission.
     */
    int lightCoords(int state, int index) {
        if ((t.stateFlags[state] & StateFlags.EMISSIVE) != 0) {
            return 0xF000F0;
        }
        int light = s.light[index];
        int block = light & 15;
        int packed = block << 4 | ((light >> 4) & 15) << 20;
        int emission = t.lightEmission[state];
        return block < emission ? (packed & 0xFF0000) | emission << 4 : packed;
    }

    float shade(int state, int index) {
        if ((t.stateFlags[state] & StateFlags.DYNAMIC_SHAPE) != 0) {
            int entry = dynamicEntry[index];
            if (entry >= 0) {
                return s.dynamicShade[entry];
            }
        }
        return t.shade[state];
    }

    boolean collisionFull(int state, int index) {
        if ((t.stateFlags[state] & StateFlags.DYNAMIC_SHAPE) != 0) {
            int entry = dynamicEntry[index];
            if (entry >= 0) {
                return (s.dynamicBits[entry] & Snapshot.DYNAMIC_COLLISION_FULL) != 0;
            }
        }
        return (t.stateFlags[state] & StateFlags.COLLISION_FULL) != 0;
    }

    boolean sturdy(int state, int index, int direction) {
        if ((t.stateFlags[state] & StateFlags.DYNAMIC_SHAPE) != 0) {
            int entry = dynamicEntry[index];
            if (entry >= 0) {
                return (s.dynamicBits[entry] >> Snapshot.DYNAMIC_STURDY_SHIFT & 1 << direction) != 0;
            }
        }
        return (t.stateFlags[state] >> StateFlags.STURDY_SHIFT & 1 << direction) != 0;
    }

    /** A tinted block's color for a tint source (-1 for none); see {@link Snapshot#addTint}. */
    int modelTint(int blockIndex, int tintIndex) {
        int entry = tintEntry[blockIndex];
        if (entry < 0 || tintIndex >= s.tintModelCount[entry]) {
            return -1;
        }
        return s.tintValues[s.tintStart[entry] + tintIndex];
    }

    int fluidTint(int blockIndex) {
        int entry = tintEntry[blockIndex];
        if (entry < 0 || !s.tintHasFluid[entry]) {
            return -1;
        }
        return s.tintValues[s.tintStart[entry] + s.tintModelCount[entry]];
    }

    Snapshot snapshot() {
        return s;
    }

    int step(int direction) {
        return step[direction];
    }

    /**
     * Writes a vertex; quads are four in a row. Notes each quad's facing from its corners (for
     * {@link #groupByFacing} and {@link FixedOrder}), and a translucent quad's center as the game
     * does: halfway between its first and third vertex.
     */
    void vertex(int layer, float x, float y, float z, int color, float u, float v, int light) {
        VertexFormats.put(format, out.vertices[layer], x, y, z, color, u, v, light);
        int count = out.vertexCounts[layer]++;
        float[] quad = corners[layer];
        int corner = (count & 3) * 3;
        quad[corner] = x;
        quad[corner + 1] = y;
        quad[corner + 2] = z;
        if ((count & 3) == 3) {
            int index = count >> 2;
            byte[] facings = quadFacings[layer];
            if (index == facings.length) {
                facings = Arrays.copyOf(facings, facings.length * 2);
                quadFacings[layer] = facings;
            }
            int facing = Facings.of(
                    quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], quad[6], quad[7], quad[8], quad[9], quad[10],
                    quad[11]);
            facings[index] = (byte) facing;
            if (layer == MeshTables.LAYER_TRANSLUCENT) {
                if (index == translucentPlanes.length) {
                    translucentPlanes = Arrays.copyOf(translucentPlanes, translucentPlanes.length * 2);
                }
                // Down and up lie across y, north and south across z, west and east across x.
                int axis = facing >> 1;
                translucentPlanes[index] = axis == 0 ? quad[1] : axis == 1 ? quad[2] : quad[0];
            }
        }
        if (layer == MeshTables.LAYER_TRANSLUCENT) {
            if ((count & 3) == 0) {
                firstX = x;
                firstY = y;
                firstZ = z;
            } else if ((count & 3) == 2) {
                out.addCentroid((firstX + x) / 2.0F, (firstY + y) / 2.0F, (firstZ + z) / 2.0F);
            }
        }
    }

    /** Writes the snapshot's finished quads (block entities drawn as part of it) as they are. */
    private void bakedQuads() {
        for (int q = 0; q < s.bakedCount; q++) {
            int light = s.bakedLight[q];
            for (int v = 0; v < 4; v++) {
                int p = q * 12 + v * 3;
                vertex(
                        MeshTables.LAYER_CUTOUT,
                        s.bakedPositions[p],
                        s.bakedPositions[p + 1],
                        s.bakedPositions[p + 2],
                        s.bakedColors[q * 4 + v],
                        s.bakedUvs[q * 8 + v * 2],
                        s.bakedUvs[q * 8 + v * 2 + 1],
                        light);
            }
        }
    }

    /** Notes that the section draws animated sprite {@code id} of the tables. */
    void markAnimated(int id) {
        out.markAnimated(id);
    }

    /**
     * Rewrites a layer's quads grouped by facing, in {@link Facings#runOf}'s order, each group in the
     * order its quads were written, and counts each run (ADR 0030).
     */
    private void groupByFacing(int layer) {
        int quads = out.vertexCounts[layer] >> 2;
        if (quads == 0) {
            return;
        }
        byte[] facings = quadFacings[layer];
        int[] runs = out.facingRuns;
        int base = layer * Facings.COUNT;
        boolean ordered = true;
        int previous = 0;
        for (int q = 0; q < quads; q++) {
            int run = Facings.runOf(facings[q]);
            runs[base + run]++;
            ordered &= run >= previous;
            previous = run;
        }
        if (ordered) {
            return;
        }
        int quadBytes = out.vertexSize * 4;
        int total = quads * quadBytes;
        if (regroup.length < total) {
            regroup = new byte[Math.max(total, regroup.length * 2)];
        }
        byte[] data = out.vertices[layer].array();
        System.arraycopy(data, 0, regroup, 0, total);
        int start = 0;
        for (int run = 0; run < Facings.COUNT; run++) {
            runStart[run] = start;
            start += runs[base + run];
        }
        for (int q = 0; q < quads; q++) {
            int run = Facings.runOf(facings[q]);
            System.arraycopy(regroup, q * quadBytes, data, runStart[run]++ * quadBytes, quadBytes);
        }
    }

    // ---- Models ---------------------------------------------------------------------------------

    private void model(int x, int y, int z, int index, int state, int f) {
        int node = t.modelNode[state];
        if (node < 0) {
            return;
        }
        int packedSeedOffset = t.seedOffset[state];
        int wx = s.originX + x;
        int wy = s.originY + y;
        int wz = s.originZ + z;
        random.setSeed(GameMath.positionSeed(
                wx + (packedSeedOffset & 3) - 1,
                wy + (packedSeedOffset >> 2 & 3) - 1,
                wz + (packedSeedOffset >> 4 & 3) - 1));
        partCount = 0;
        collect(node);
        if (partCount == 0) {
            return;
        }
        float offsetX = 0.0F;
        float offsetY = 0.0F;
        float offsetZ = 0.0F;
        int table = t.offsetTable[state];
        if (table >= 0) {
            long offsetSeed = GameMath.positionSeed(wx, 0, wz);
            offsetX = t.offsets[table * 48 + (int) (offsetSeed & 15L)];
            offsetY = t.offsets[table * 48 + 16 + (int) (offsetSeed >> 4 & 15L)];
            offsetZ = t.offsets[table * 48 + 32 + (int) (offsetSeed >> 8 & 15L)];
        }
        float bx = x + offsetX;
        float by = y + offsetY;
        float bz = z + offsetZ;
        boolean solidLayer = (f & StateFlags.FORCE_SOLID_LAYER) != 0;
        boolean smooth = t.ambientOcclusion && t.lightEmission[state] == 0 && t.partAmbientOcclusion[parts[0]] != 0;
        int blockIndex = Snapshot.blockIndex(x, y, z);

        // Which faces show, decided once per direction for all of the block's parts.
        int decided = 0;
        int shown = 0;
        for (int p = 0; p < partCount; p++) {
            int part = parts[p];
            for (int d = 0; d < 6; d++) {
                int bit = 1 << d;
                if ((decided & bit) != 0 && (shown & bit) == 0) {
                    continue;
                }
                int from = t.partFaces[part * 8 + d];
                int to = t.partFaces[part * 8 + d + 1];
                if (from == to) {
                    continue;
                }
                if ((decided & bit) == 0) {
                    decided |= bit;
                    if (faceShows(state, index, d)) {
                        shown |= bit;
                    }
                }
                if ((shown & bit) != 0) {
                    int flatLight = smooth ? 0 : lightCoords(state, index + step[d]);
                    for (int i = from; i < to; i++) {
                        quad(t.partQuads[i], state, index, blockIndex, bx, by, bz, smooth, flatLight, solidLayer);
                    }
                }
            }
            int from = t.partFaces[part * 8 + 6];
            int to = t.partFaces[part * 8 + 7];
            for (int i = from; i < to; i++) {
                quad(t.partQuads[i], state, index, blockIndex, bx, by, bz, smooth, -1, solidLayer);
            }
        }
    }

    /** Walks a model node the way the game collects a block's parts from its random. */
    private void collect(int node) {
        int[] nodes = t.nodes;
        switch (nodes[node]) {
            case MeshTables.NODE_SINGLE -> {
                if (partCount == parts.length) {
                    parts = Arrays.copyOf(parts, partCount * 2);
                }
                parts[partCount++] = nodes[node + 1];
            }
            case MeshTables.NODE_WEIGHTED -> {
                int selection = random.nextInt(nodes[node + 1]);
                int count = nodes[node + 2];
                for (int i = 0; i < count; i++) {
                    selection -= nodes[node + 3 + 2 * i];
                    if (selection < 0) {
                        collect(nodes[node + 4 + 2 * i]);
                        return;
                    }
                }
                throw new IllegalStateException("Weighted model selection past its total");
            }
            case MeshTables.NODE_LIST -> {
                int count = nodes[node + 1];
                for (int i = 0; i < count; i++) {
                    if (partCount == parts.length) {
                        parts = Arrays.copyOf(parts, partCount * 2);
                    }
                    parts[partCount++] = nodes[node + 2 + i];
                }
            }
            default -> {
                long seed = random.nextLong();
                int count = nodes[node + 1];
                for (int i = 0; i < count; i++) {
                    random.setSeed(seed);
                    collect(nodes[node + 2 + i]);
                }
            }
        }
    }

    /** Whether a block's face toward {@code direction} shows past its neighbor there. */
    private boolean faceShows(int state, int index, int direction) {
        int neighbor = s.states[index + step[direction]];
        int occluder = t.faceShape[neighbor * 6 + Dirs.opposite(direction)];
        if (occluder == MeshTables.SHAPE_FULL) {
            return false;
        }
        if ((t.stateFlags[state] & StateFlags.SKIP_RULE) != 0 && skips(state, neighbor, direction)) {
            return false;
        }
        if (occluder == MeshTables.SHAPE_EMPTY) {
            return true;
        }
        int shape = t.faceShape[state * 6 + direction];
        if (shape == MeshTables.SHAPE_EMPTY) {
            return true;
        }
        int bit = shape * t.shapeCount + occluder;
        return (t.shapeCovered[bit >> 6] & 1L << bit) == 0;
    }

    private boolean skips(int state, int neighbor, int direction) {
        int a = t.skipClass[state];
        int b = t.skipClass[neighbor];
        if (a < 0 || b < 0) {
            return false;
        }
        int bit = (a * t.skipClassCount + b) * 6 + direction;
        return (t.skipTable[bit >> 6] & 1L << bit) != 0;
    }

    /**
     * Lights, tints and writes one quad.
     *
     * @param flatLight the face's light for a culled face lit flat, or -1 for an unculled face,
     *     whose light depends on its shape
     */
    private void quad(
            int q,
            int state,
            int index,
            int blockIndex,
            float bx,
            float by,
            float bz,
            boolean smooth,
            int flatLight,
            boolean solidLayer) {
        int info = t.quadInfo[q];
        int animated = t.quadAnimated[q];
        if (animated != 0) {
            out.markAnimated(animated - 1);
        }
        float brightness = s.cardinalLighting[Quads.shadeDirection(info)];
        if (smooth) {
            smoothLight(q, info, state, index, brightness);
        } else {
            int light = flatLight;
            if (light == -1) {
                int direction = Quads.direction(info);
                boolean cubic =
                        (info & Quads.FLAT) != 0 && ((info & Quads.ON_EDGE) != 0 || collisionFull(state, index));
                light = lightCoords(state, cubic ? index + step[direction] : index);
            }
            int gray = gray(brightness);
            for (int v = 0; v < 4; v++) {
                vertexLight[v] = light;
                vertexColor[v] = gray;
            }
        }
        int tintIndex = Quads.tintIndex(info);
        if (tintIndex != -1) {
            int tint = modelTint(blockIndex, tintIndex);
            for (int v = 0; v < 4; v++) {
                vertexColor[v] = multiply(vertexColor[v], tint);
            }
        }
        int layer = solidLayer ? MeshTables.LAYER_SOLID : Quads.layer(info);
        int emission = Quads.lightEmission(info);
        float[] positions = t.quadPositions;
        float[] uvs = t.quadUvs;
        for (int v = 0; v < 4; v++) {
            vertex(
                    layer,
                    positions[q * 12 + v * 3] + bx,
                    positions[q * 12 + v * 3 + 1] + by,
                    positions[q * 12 + v * 3 + 2] + bz,
                    vertexColor[v],
                    uvs[q * 8 + v * 2],
                    uvs[q * 8 + v * 2 + 1],
                    AmbientOcclusion.withEmission(vertexLight[v], emission));
        }
    }

    /** The game's smooth lighting of one quad: {@link #vertexColor} and {@link #vertexLight}. */
    private void smoothLight(int q, int info, int state, int index, float brightness) {
        int direction = Quads.direction(info);
        char[] states = s.states;
        int[] flags = t.stateFlags;
        boolean cubic = (info & Quads.FLAT) != 0 && ((info & Quads.ON_EDGE) != 0 || collisionFull(state, index));
        int base = cubic ? index + step[direction] : index;
        int[] corners = AmbientOcclusion.CORNERS[direction];
        int forward = step[direction];

        int p0 = base + step[corners[0]];
        int p1 = base + step[corners[1]];
        int p2 = base + step[corners[2]];
        int p3 = base + step[corners[3]];
        int s0 = states[p0];
        int s1 = states[p1];
        int s2 = states[p2];
        int s3 = states[p3];
        int light0 = lightCoords(s0, p0);
        int light1 = lightCoords(s1, p1);
        int light2 = lightCoords(s2, p2);
        int light3 = lightCoords(s3, p3);
        float shade0 = shade(s0, p0);
        float shade1 = shade(s1, p1);
        float shade2 = shade(s2, p2);
        float shade3 = shade(s3, p3);
        boolean open0 = (flags[states[p0 + forward]] & StateFlags.LIGHT_PERMEABLE) != 0;
        boolean open1 = (flags[states[p1 + forward]] & StateFlags.LIGHT_PERMEABLE) != 0;
        boolean open2 = (flags[states[p2 + forward]] & StateFlags.LIGHT_PERMEABLE) != 0;
        boolean open3 = (flags[states[p3 + forward]] & StateFlags.LIGHT_PERMEABLE) != 0;

        // The diagonals, read only where light could reach them; else the first corner stands in
        // (for all four: the game's choice, kept).
        float shade02 = shade0;
        int light02 = light0;
        if (open2 || open0) {
            int p = p0 + step[corners[2]];
            shade02 = shade(states[p], p);
            light02 = lightCoords(states[p], p);
        }
        float shade03 = shade0;
        int light03 = light0;
        if (open3 || open0) {
            int p = p0 + step[corners[3]];
            shade03 = shade(states[p], p);
            light03 = lightCoords(states[p], p);
        }
        float shade12 = shade0;
        int light12 = light0;
        if (open2 || open1) {
            int p = p1 + step[corners[2]];
            shade12 = shade(states[p], p);
            light12 = lightCoords(states[p], p);
        }
        float shade13 = shade0;
        int light13 = light0;
        if (open3 || open1) {
            int p = p1 + step[corners[3]];
            shade13 = shade(states[p], p);
            light13 = lightCoords(states[p], p);
        }

        int lightCenter = lightCoords(state, index);
        int next = index + forward;
        int nextState = states[next];
        if (cubic || (flags[nextState] & StateFlags.SOLID_RENDER) == 0) {
            lightCenter = lightCoords(nextState, next);
        }
        float shadeCenter = cubic ? shade(states[base], base) : shade(state, index);

        float sample1 = (shade3 + shade0 + shade03 + shadeCenter) * 0.25F;
        float sample2 = (shade2 + shade0 + shade02 + shadeCenter) * 0.25F;
        float sample3 = (shade2 + shade1 + shade12 + shadeCenter) * 0.25F;
        float sample4 = (shade3 + shade1 + shade13 + shadeCenter) * 0.25F;
        int blend1 = AmbientOcclusion.blend(light3, light0, light03, lightCenter);
        int blend2 = AmbientOcclusion.blend(light2, light0, light02, lightCenter);
        int blend3 = AmbientOcclusion.blend(light2, light1, light12, lightCenter);
        int blend4 = AmbientOcclusion.blend(light3, light1, light13, lightCenter);
        int[] vertexOf = AmbientOcclusion.VERTEX_OF_CORNER[direction];
        if ((info & Quads.PARTIAL) != 0) {
            float[] weights = t.quadAoWeights;
            for (int corner = 0; corner < 4; corner++) {
                int w = q * 16 + corner * 4;
                float w1 = weights[w];
                float w2 = weights[w + 1];
                float w3 = weights[w + 2];
                float w4 = weights[w + 3];
                int vertex = vertexOf[corner];
                vertexColor[vertex] = gray(clampUnit(sample1 * w1 + sample2 * w2 + sample3 * w3 + sample4 * w4));
                vertexLight[vertex] = AmbientOcclusion.weightedBlend(blend1, blend2, blend3, blend4, w1, w2, w3, w4);
            }
        } else {
            vertexLight[vertexOf[0]] = blend1;
            vertexLight[vertexOf[1]] = blend2;
            vertexLight[vertexOf[2]] = blend3;
            vertexLight[vertexOf[3]] = blend4;
            vertexColor[vertexOf[0]] = gray(sample1);
            vertexColor[vertexOf[1]] = gray(sample2);
            vertexColor[vertexOf[2]] = gray(sample3);
            vertexColor[vertexOf[3]] = gray(sample4);
        }
        for (int v = 0; v < 4; v++) {
            vertexColor[v] = scale(vertexColor[v], brightness);
        }
    }

    // ---- The game's color arithmetic ----------------------------------------------------------

    /** Clamps to 0..1, letting NaN through as the game's clamp does. */
    private static float clampUnit(float value) {
        return value < 0.0F ? 0.0F : value > 1.0F ? 1.0F : value;
    }

    /** An opaque gray of {@code brightness}: the 8-bit channel rounded down. */
    static int gray(float brightness) {
        int channel = floor(brightness * 255.0F);
        return 0xFF000000 | (channel & 0xFF) << 16 | (channel & 0xFF) << 8 | (channel & 0xFF);
    }

    static int floor(float value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** Scales the color channels (not alpha), each truncated and clamped to 0..255. */
    static int scale(int color, float factor) {
        int r = clampChannel((int) ((color >> 16 & 0xFF) * factor));
        int g = clampChannel((int) ((color >> 8 & 0xFF) * factor));
        int b = clampChannel((int) ((color & 0xFF) * factor));
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int clampChannel(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Multiplies two ARGB colors channel by channel; -1 (opaque white) leaves the other as it is. */
    static int multiply(int a, int b) {
        if (a == -1) {
            return b;
        }
        if (b == -1) {
            return a;
        }
        int alpha = (a >>> 24) * (b >>> 24) / 255;
        int red = (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255;
        int green = (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255;
        int blue = (a & 0xFF) * (b & 0xFF) / 255;
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
    }
}
