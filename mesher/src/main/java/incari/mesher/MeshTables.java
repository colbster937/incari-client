package incari.mesher;

import java.util.Arrays;

/**
 * Everything the mesher needs to know about the game's blocks, as flat arrays indexed by block
 * state id: flags, light emission, shade, the shapes that hide faces, fluid heights, and every
 * model's quads. Built from the game's baked models when they load or a mesh setting changes, then
 * sent once to each worker.
 *
 * <p>Worker-side, no game objects. {@link Builder} is how the game's side fills it
 * ({@code incari.client.render.mesher.MeshTableExport}).
 */
public final class MeshTables {

    /** Bumped when the encoding changes; a worker refuses tables of another version. */
    static final int ENCODING = 2;

    /** A quad's layer: the game's {@code ChunkSectionLayer} ordinals. */
    public static final int LAYER_SOLID = 0;

    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;
    public static final int LAYER_COUNT = 3;

    /** Face shape ids ({@link #faceShape}): the empty shape and the full block, by identity. */
    public static final int SHAPE_EMPTY = 0;

    public static final int SHAPE_FULL = 1;

    /** How a state's face hides a fluid face against it ({@link #fluidCoverKind}). */
    public static final int FLUID_COVER_NONE = 0;

    public static final int FLUID_COVER_FULL_BLOCK = 1;
    public static final int FLUID_COVER_PARTIAL = 2;

    static final int NODE_SINGLE = 0;
    static final int NODE_WEIGHTED = 1;
    static final int NODE_MULTIPART = 2;
    static final int NODE_LIST = 3;

    final boolean ambientOcclusion;
    final int stateCount;
    final int[] stateFlags;
    final byte[] lightEmission;
    final float[] shade;
    /** Per state and direction: the id of the face's occlusion shape. */
    final short[] faceShape;
    /** 0 for none; states whose fluids are the "same" fluid share a group. */
    final byte[] fluidGroup;

    final float[] fluidHeight;
    final byte[] fluidModel;
    /** Per state and direction: how the state's face toward that direction hides a fluid face. */
    final byte[] fluidCoverKind;
    /** Per state and direction: with a partial cover, the fluid height up to which it hides it. */
    final float[] fluidCoverHeight;

    final int[] modelNode;
    final short[] skipClass;
    final short[] offsetTable;
    final byte[] seedOffset;

    final int shapeCount;
    /** Bit {@code a * shapeCount + b}: face shape a is entirely behind occluding shape b. */
    final long[] shapeCovered;

    final int skipClassCount;
    /** Bit {@code (a * skipClassCount + b) * 6 + direction}: a's face hides against b. */
    final long[] skipTable;

    final int[] nodes;
    /** Per part: the start of each face's quad list (6 directions, then unculled), then the end. */
    final int[] partFaces;

    final int[] partQuads;
    final byte[] partAmbientOcclusion;

    final float[] quadPositions;
    final float[] quadUvs;
    /** Direction, layer, tint index, shade direction, light emission and shape bits; see {@link Quads}. */
    final int[] quadInfo;
    /** Per quad, per vertex in the lighter's order, the weights of the four corner samples. */
    final float[] quadAoWeights;
    /** Per quad: its bounding box, min x y z then max x y z. */
    final float[] quadBounds;
    /** Per quad: 1 + the id of the animated sprite it's textured with, or 0 (ADR 0030). */
    final short[] quadAnimated;

    /** Per offset table: x, y and z offsets for each of the 16 values of their seed's nibble. */
    final float[] offsets;

    final int fluidModelCount;
    final byte[] fluidModelLayer;
    /** Per fluid model: still, flowing and overlay sprites' u0, u1, v0, v1. */
    final float[] fluidSprites;

    final byte[] fluidModelFlags;
    /** Per fluid model: 1 + the animated sprite id of its still, flowing and overlay sprites, or 0. */
    final short[] fluidAnimated;

    static final int FLUID_MODEL_OVERLAY = 1;
    static final int FLUID_MODEL_TINTED = 2;

    private MeshTables(Builder b) {
        ambientOcclusion = b.ambientOcclusion;
        stateCount = b.stateCount;
        stateFlags = b.stateFlags;
        lightEmission = b.lightEmission;
        shade = b.shade;
        faceShape = b.faceShape;
        fluidGroup = b.fluidGroup;
        fluidHeight = b.fluidHeight;
        fluidModel = b.fluidModel;
        fluidCoverKind = b.fluidCoverKind;
        fluidCoverHeight = b.fluidCoverHeight;
        modelNode = b.modelNode;
        skipClass = b.skipClass;
        offsetTable = b.offsetTable;
        seedOffset = b.seedOffset;
        shapeCount = b.shapeCount;
        shapeCovered = b.shapeCovered;
        skipClassCount = b.skipClassCount;
        skipTable = b.skipTable;
        nodes = Arrays.copyOf(b.nodes, b.nodeSize);
        partFaces = Arrays.copyOf(b.partFaces, b.partCount * 8);
        partQuads = Arrays.copyOf(b.partQuads, b.partQuadSize);
        partAmbientOcclusion = Arrays.copyOf(b.partAo, b.partCount);
        quadPositions = Arrays.copyOf(b.quadPositions, b.quadCount * 12);
        quadUvs = Arrays.copyOf(b.quadUvs, b.quadCount * 8);
        quadInfo = Arrays.copyOf(b.quadInfo, b.quadCount);
        offsets = Arrays.copyOf(b.offsets, b.offsetCount * 48);
        fluidModelCount = b.fluidModelCount;
        fluidModelLayer = Arrays.copyOf(b.fluidModelLayer, b.fluidModelCount);
        fluidSprites = Arrays.copyOf(b.fluidSprites, b.fluidModelCount * 12);
        fluidModelFlags = Arrays.copyOf(b.fluidModelFlags, b.fluidModelCount);
        quadAnimated = Arrays.copyOf(b.quadAnimated, b.quadCount);
        fluidAnimated = Arrays.copyOf(b.fluidAnimated, b.fluidModelCount * 3);
        quadBounds = new float[quadInfo.length * 6];
        quadAoWeights = new float[quadInfo.length * 16];
        Quads.derive(this);
    }

    public int stateCount() {
        return stateCount;
    }

    public int flags(int state) {
        return stateFlags[state];
    }

    /** The state's fluid group: 0 for no fluid; the same for fluids that count as the same. */
    public int fluidGroup(int state) {
        return fluidGroup[state];
    }

    /** Whether the state's fluid is drawn with a tint (the snapshot then carries its color). */
    public boolean fluidTinted(int state) {
        return fluidGroup[state] != 0 && (fluidModelFlags[fluidModel[state]] & FLUID_MODEL_TINTED) != 0;
    }

    public boolean ambientOcclusion() {
        return ambientOcclusion;
    }

    public int quadCount() {
        return quadInfo.length;
    }

    public void encode(Bytes.Out out) {
        out.putInt(ENCODING);
        out.putByte(ambientOcclusion ? 1 : 0);
        out.putInt(stateCount);
        out.putInts(stateFlags, stateCount);
        out.putByteArray(lightEmission, stateCount);
        out.putFloats(shade, stateCount);
        out.putShorts(faceShape, stateCount * 6);
        out.putByteArray(fluidGroup, stateCount);
        out.putFloats(fluidHeight, stateCount);
        out.putByteArray(fluidModel, stateCount);
        out.putByteArray(fluidCoverKind, stateCount * 6);
        out.putFloats(fluidCoverHeight, stateCount * 6);
        out.putInts(modelNode, stateCount);
        out.putShorts(skipClass, stateCount);
        out.putShorts(offsetTable, stateCount);
        out.putByteArray(seedOffset, stateCount);
        out.putInt(shapeCount);
        out.putLongs(shapeCovered, shapeCovered.length);
        out.putInt(skipClassCount);
        out.putLongs(skipTable, skipTable.length);
        out.putInts(nodes, nodes.length);
        out.putInts(partFaces, partFaces.length);
        out.putInts(partQuads, partQuads.length);
        out.putByteArray(partAmbientOcclusion, partAmbientOcclusion.length);
        out.putFloats(quadPositions, quadPositions.length);
        out.putFloats(quadUvs, quadUvs.length);
        out.putInts(quadInfo, quadInfo.length);
        out.putFloats(offsets, offsets.length);
        out.putInt(fluidModelCount);
        out.putByteArray(fluidModelLayer, fluidModelCount);
        out.putFloats(fluidSprites, fluidSprites.length);
        out.putByteArray(fluidModelFlags, fluidModelCount);
        out.putShorts(quadAnimated, quadAnimated.length);
        out.putShorts(fluidAnimated, fluidAnimated.length);
    }

    public static MeshTables decode(Bytes.In in) {
        int encoding = in.getInt();
        if (encoding != ENCODING) {
            throw new IllegalStateException("Mesh tables of encoding " + encoding + ", expected " + ENCODING);
        }
        boolean ambientOcclusion = in.getByte() != 0;
        Builder b = new Builder(in.getInt(), ambientOcclusion);
        b.stateFlags = in.getInts();
        b.lightEmission = in.getByteArray();
        b.shade = in.getFloats();
        b.faceShape = in.getShorts();
        b.fluidGroup = in.getByteArray();
        b.fluidHeight = in.getFloats();
        b.fluidModel = in.getByteArray();
        b.fluidCoverKind = in.getByteArray();
        b.fluidCoverHeight = in.getFloats();
        b.modelNode = in.getInts();
        b.skipClass = in.getShorts();
        b.offsetTable = in.getShorts();
        b.seedOffset = in.getByteArray();
        b.shapeCount = in.getInt();
        b.shapeCovered = in.getLongs();
        b.skipClassCount = in.getInt();
        b.skipTable = in.getLongs();
        b.nodes = in.getInts();
        b.nodeSize = b.nodes.length;
        b.partFaces = in.getInts();
        b.partCount = b.partFaces.length / 8;
        b.partQuads = in.getInts();
        b.partQuadSize = b.partQuads.length;
        b.partAo = in.getByteArray();
        b.quadPositions = in.getFloats();
        b.quadUvs = in.getFloats();
        b.quadInfo = in.getInts();
        b.quadCount = b.quadInfo.length;
        b.offsets = in.getFloats();
        b.offsetCount = b.offsets.length / 48;
        b.fluidModelCount = in.getInt();
        b.fluidModelLayer = in.getByteArray();
        b.fluidSprites = in.getFloats();
        b.fluidModelFlags = in.getByteArray();
        b.quadAnimated = in.getShorts();
        b.fluidAnimated = in.getShorts();
        return new MeshTables(b);
    }

    /** Fills the tables from the game's side. Every state starts as air with no model. */
    public static final class Builder {

        final boolean ambientOcclusion;
        final int stateCount;
        int[] stateFlags;
        byte[] lightEmission;
        float[] shade;
        short[] faceShape;
        byte[] fluidGroup;
        float[] fluidHeight;
        byte[] fluidModel;
        byte[] fluidCoverKind;
        float[] fluidCoverHeight;
        int[] modelNode;
        short[] skipClass;
        short[] offsetTable;
        byte[] seedOffset;
        int shapeCount = 2;
        long[] shapeCovered = new long[1];
        int skipClassCount;
        long[] skipTable = new long[0];
        int[] nodes = new int[1024];
        int nodeSize;
        int[] partFaces = new int[1024];
        int partCount;
        int[] partQuads = new int[1024];
        int partQuadSize;
        byte[] partAo = new byte[128];
        float[] quadPositions = new float[12 * 1024];
        float[] quadUvs = new float[8 * 1024];
        int[] quadInfo = new int[1024];
        int quadCount;
        float[] offsets = new float[48 * 16];
        int offsetCount;
        int fluidModelCount;
        byte[] fluidModelLayer = new byte[8];
        float[] fluidSprites = new float[12 * 8];
        byte[] fluidModelFlags = new byte[8];
        short[] quadAnimated = new short[1024];
        short[] fluidAnimated = new short[3 * 8];

        public Builder(int stateCount, boolean ambientOcclusion) {
            this.stateCount = stateCount;
            this.ambientOcclusion = ambientOcclusion;
            stateFlags = new int[stateCount];
            Arrays.fill(stateFlags, StateFlags.AIR);
            lightEmission = new byte[stateCount];
            shade = new float[stateCount];
            Arrays.fill(shade, 1.0F);
            faceShape = new short[stateCount * 6];
            fluidGroup = new byte[stateCount];
            fluidHeight = new float[stateCount];
            fluidModel = new byte[stateCount];
            fluidCoverKind = new byte[stateCount * 6];
            fluidCoverHeight = new float[stateCount * 6];
            modelNode = new int[stateCount];
            Arrays.fill(modelNode, -1);
            skipClass = new short[stateCount];
            Arrays.fill(skipClass, (short) -1);
            offsetTable = new short[stateCount];
            Arrays.fill(offsetTable, (short) -1);
            seedOffset = new byte[stateCount];
            Arrays.fill(seedOffset, (byte) (1 | 1 << 2 | 1 << 4));
        }

        public void state(int state, int flags, int lightEmission, float shade) {
            stateFlags[state] = flags;
            this.lightEmission[state] = (byte) lightEmission;
            this.shade[state] = shade;
        }

        public void faceShape(int state, int direction, int shapeId) {
            faceShape[state * 6 + direction] = (short) shapeId;
        }

        public void fluid(int state, int group, float ownHeight, int model) {
            fluidGroup[state] = (byte) group;
            fluidHeight[state] = ownHeight;
            fluidModel[state] = (byte) model;
        }

        public void fluidCover(int state, int direction, int kind, float height) {
            fluidCoverKind[state * 6 + direction] = (byte) kind;
            fluidCoverHeight[state * 6 + direction] = height;
        }

        public void model(int state, int node) {
            modelNode[state] = node;
        }

        public void skipClass(int state, int skipClass) {
            this.skipClass[state] = (short) skipClass;
        }

        public void offsetTable(int state, int table) {
            offsetTable[state] = (short) table;
        }

        /** Where the state's position seed is taken from, relative to its own position (-1 to 1). */
        public void seedOffset(int state, int dx, int dy, int dz) {
            seedOffset[state] = (byte) ((dx + 1) | (dy + 1) << 2 | (dz + 1) << 4);
        }

        /**
         * The face shapes: ids from 2 up are the distinct shapes other than the empty one and the
         * full block, and {@code covered.test(a, b)} says whether shape a is entirely behind b
         * (for every pair of ids, 0 and 1 included).
         */
        public void faceShapes(int count, ShapePairs covered) {
            shapeCount = count;
            shapeCovered = new long[(count * count + 63) >> 6];
            for (int a = 0; a < count; a++) {
                for (int b = 0; b < count; b++) {
                    if (covered.test(a, b)) {
                        int bit = a * count + b;
                        shapeCovered[bit >> 6] |= 1L << bit;
                    }
                }
            }
        }

        /** The skip classes, and whether a face of class a hides against class b toward a direction. */
        public void skipClasses(int count, SkipRule rule) {
            skipClassCount = count;
            skipTable = new long[(count * count * 6 + 63) >> 6];
            for (int a = 0; a < count; a++) {
                for (int b = 0; b < count; b++) {
                    for (int d = 0; d < 6; d++) {
                        if (rule.skips(a, b, d)) {
                            int bit = (a * count + b) * 6 + d;
                            skipTable[bit >> 6] |= 1L << bit;
                        }
                    }
                }
            }
        }

        /** Adds a quad and returns its index. */
        public int quad(
                float[] positions,
                float[] uvs,
                int direction,
                int layer,
                int tintIndex,
                int shadeDirection,
                int lightEmission) {
            if (quadCount == quadInfo.length) {
                quadInfo = Arrays.copyOf(quadInfo, quadCount * 2);
                quadPositions = Arrays.copyOf(quadPositions, quadCount * 2 * 12);
                quadUvs = Arrays.copyOf(quadUvs, quadCount * 2 * 8);
                quadAnimated = Arrays.copyOf(quadAnimated, quadCount * 2);
            }
            System.arraycopy(positions, 0, quadPositions, quadCount * 12, 12);
            System.arraycopy(uvs, 0, quadUvs, quadCount * 8, 8);
            quadInfo[quadCount] = Quads.pack(direction, layer, tintIndex, shadeDirection, lightEmission);
            return quadCount++;
        }

        /**
         * Adds a model part: its quads for each face (index 0 to 5 by direction, 6 for the ones
         * never culled), and returns its index.
         */
        public int part(int[][] quadsByFace, boolean ambientOcclusion) {
            if ((partCount + 1) * 8 > partFaces.length) {
                partFaces = Arrays.copyOf(partFaces, partFaces.length * 2);
                partAo = Arrays.copyOf(partAo, partAo.length * 2);
            }
            for (int face = 0; face < 7; face++) {
                partFaces[partCount * 8 + face] = partQuadSize;
                for (int quad : quadsByFace[face]) {
                    if (partQuadSize == partQuads.length) {
                        partQuads = Arrays.copyOf(partQuads, partQuadSize * 2);
                    }
                    partQuads[partQuadSize++] = quad;
                }
            }
            partFaces[partCount * 8 + 7] = partQuadSize;
            partAo[partCount] = (byte) (ambientOcclusion ? 1 : 0);
            return partCount++;
        }

        public int singleNode(int part) {
            int at = reserveNodes(2);
            nodes[at] = NODE_SINGLE;
            nodes[at + 1] = part;
            return at;
        }

        /** A weighted choice: {@code children[i]} with {@code weights[i]}, drawn from the random. */
        public int weightedNode(int[] weights, int[] children) {
            int at = reserveNodes(3 + 2 * weights.length);
            int total = 0;
            for (int weight : weights) {
                total += weight;
            }
            nodes[at] = NODE_WEIGHTED;
            nodes[at + 1] = total;
            nodes[at + 2] = weights.length;
            for (int i = 0; i < weights.length; i++) {
                nodes[at + 3 + 2 * i] = weights[i];
                nodes[at + 4 + 2 * i] = children[i];
            }
            return at;
        }

        /** Parts that are always there, drawing nothing from the random. */
        public int listNode(int[] parts) {
            int at = reserveNodes(2 + parts.length);
            nodes[at] = NODE_LIST;
            nodes[at + 1] = parts.length;
            System.arraycopy(parts, 0, nodes, at + 2, parts.length);
            return at;
        }

        /** Every child, each from the same seed drawn once from the random. */
        public int multipartNode(int[] children) {
            int at = reserveNodes(2 + children.length);
            nodes[at] = NODE_MULTIPART;
            nodes[at + 1] = children.length;
            System.arraycopy(children, 0, nodes, at + 2, children.length);
            return at;
        }

        private int reserveNodes(int count) {
            if (nodeSize + count > nodes.length) {
                nodes = Arrays.copyOf(nodes, Math.max(nodeSize + count, nodes.length * 2));
            }
            int at = nodeSize;
            nodeSize += count;
            return at;
        }

        /** Adds an offset table (16 x, then 16 y, then 16 z offsets) and returns its index. */
        public int offsets(float[] table) {
            if ((offsetCount + 1) * 48 > offsets.length) {
                offsets = Arrays.copyOf(offsets, offsets.length * 2);
            }
            System.arraycopy(table, 0, offsets, offsetCount * 48, 48);
            return offsetCount++;
        }

        /**
         * Adds a fluid model: its layer and sprites (still, flowing, overlay: u0, u1, v0, v1 each).
         */
        public int fluidModel(int layer, float[] sprites, boolean overlay, boolean tinted) {
            if (fluidModelCount == fluidModelLayer.length) {
                fluidModelLayer = Arrays.copyOf(fluidModelLayer, fluidModelCount * 2);
                fluidSprites = Arrays.copyOf(fluidSprites, fluidModelCount * 2 * 12);
                fluidModelFlags = Arrays.copyOf(fluidModelFlags, fluidModelCount * 2);
                fluidAnimated = Arrays.copyOf(fluidAnimated, fluidModelCount * 2 * 3);
            }
            fluidModelLayer[fluidModelCount] = (byte) layer;
            System.arraycopy(sprites, 0, fluidSprites, fluidModelCount * 12, 12);
            fluidModelFlags[fluidModelCount] =
                    (byte) ((overlay ? FLUID_MODEL_OVERLAY : 0) | (tinted ? FLUID_MODEL_TINTED : 0));
            return fluidModelCount++;
        }

        /** Marks a quad as textured with animated sprite {@code id} (0 up). */
        public void animatedQuad(int quad, int id) {
            quadAnimated[quad] = (short) (id + 1);
        }

        /**
         * The animated sprite ids of a fluid model's still, flowing and overlay sprites, -1 for one
         * that isn't animated.
         */
        public void animatedFluid(int model, int still, int flowing, int overlay) {
            fluidAnimated[model * 3] = (short) (still + 1);
            fluidAnimated[model * 3 + 1] = (short) (flowing + 1);
            fluidAnimated[model * 3 + 2] = (short) (overlay + 1);
        }

        public MeshTables build() {
            return new MeshTables(this);
        }
    }

    /** Whether face shape a is entirely behind shape b. */
    @FunctionalInterface
    public interface ShapePairs {
        boolean test(int a, int b);
    }

    /** Whether a face of skip class a toward a direction hides against a neighbor of class b. */
    @FunctionalInterface
    public interface SkipRule {
        boolean skips(int a, int b, int direction);
    }
}
