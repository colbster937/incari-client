package incari.mesher;

import java.util.Arrays;

/**
 * What meshing a section produced: each layer's vertices in the snapshot's vertex format, which
 * faces of the section can see each other (for the game's occlusion graph), and for translucent
 * geometry, each quad's center and an index buffer sorted back to front from the camera.
 *
 * <p>Reused from one mesh to the next: {@link #clear} keeps the buffers.
 */
public final class MeshResult {

    /** Bit {@code a + 6 * b}: section face a can see face b (the game's {@code VisibilitySet}). */
    public long visibility;

    final Bytes.Out[] vertices = {new Bytes.Out(64 * 1024), new Bytes.Out(16 * 1024), new Bytes.Out(16 * 1024)};
    final int[] vertexCounts = new int[MeshTables.LAYER_COUNT];

    /** The translucent quads' centers, x y z each, in the order their vertices were written. */
    float[] centroids = new float[3 * 256];

    int translucentQuads;
    final Bytes.Out sortedIndices = new Bytes.Out(4 * 1024);
    boolean intIndices;
    /** Whether the translucent layer is in its {@link FixedOrder}, which no camera needs re-sorted. */
    boolean fixedOrder;

    int vertexSize;

    /**
     * Per layer, how many quads each of its runs holds, in {@link Facings#runOf}'s order: the solid and
     * cutout layers are written grouped by facing (ADR 0030). The translucent layer keeps the order
     * it's sorted in, so all its quads count as unassigned.
     */
    final int[] facingRuns = new int[MeshTables.LAYER_COUNT * Facings.COUNT];

    /** Bit {@code id}: the section draws animated sprite {@code id} of the tables (ADR 0030). */
    long[] animatedSprites = new long[4];

    public void clear() {
        visibility = 0;
        for (Bytes.Out out : vertices) {
            out.clear();
        }
        Arrays.fill(vertexCounts, 0);
        translucentQuads = 0;
        sortedIndices.clear();
        intIndices = false;
        fixedOrder = false;
        Arrays.fill(facingRuns, 0);
        Arrays.fill(animatedSprites, 0L);
    }

    public int vertexCount(int layer) {
        return vertexCounts[layer];
    }

    /** The layer's vertex bytes: {@link #vertexCount} times the vertex size. */
    public byte[] vertexBytes(int layer) {
        return vertices[layer].array();
    }

    public int vertexByteCount(int layer) {
        return vertices[layer].size();
    }

    public int translucentQuadCount() {
        return translucentQuads;
    }

    /** The translucent quads' centers, three floats each. */
    public float[] centroids() {
        return centroids;
    }

    /** The translucent layer's index buffer, sorted back to front. */
    public byte[] sortedIndexBytes() {
        return sortedIndices.array();
    }

    public int sortedIndexByteCount() {
        return sortedIndices.size();
    }

    /** Whether the sorted indices are 32-bit (more than 65535 vertices); else 16-bit. */
    public boolean intIndices() {
        return intIndices;
    }

    /** Whether the translucent layer's order holds from every camera ({@link FixedOrder}). */
    public boolean fixedOrder() {
        return fixedOrder;
    }

    /** How many quads run {@code run} (see {@link Facings#runOf}) of a layer holds. */
    public int facingRun(int layer, int run) {
        return facingRuns[layer * Facings.COUNT + run];
    }

    /** The animated sprites the section draws, as bits by the tables' sprite id. */
    public long[] animatedSprites() {
        return animatedSprites;
    }

    void markAnimated(int id) {
        if (id >> 6 >= animatedSprites.length) {
            animatedSprites = Arrays.copyOf(animatedSprites, Math.max(animatedSprites.length * 2, (id >> 6) + 1));
        }
        animatedSprites[id >> 6] |= 1L << id;
    }

    public boolean isEmpty() {
        return vertexCounts[0] == 0 && vertexCounts[1] == 0 && vertexCounts[2] == 0;
    }

    void addCentroid(float x, float y, float z) {
        if (translucentQuads * 3 + 3 > centroids.length) {
            centroids = Arrays.copyOf(centroids, centroids.length * 2);
        }
        centroids[translucentQuads * 3] = x;
        centroids[translucentQuads * 3 + 1] = y;
        centroids[translucentQuads * 3 + 2] = z;
        translucentQuads++;
    }

    public void encode(Bytes.Out out) {
        out.putLong(visibility);
        out.putByte(vertexSize);
        for (int layer = 0; layer < MeshTables.LAYER_COUNT; layer++) {
            out.putInt(vertexCounts[layer]);
            out.putByteArray(vertices[layer].array(), vertices[layer].size());
        }
        out.putFloats(centroids, translucentQuads * 3);
        out.putByte(intIndices ? 1 : 0);
        out.putByteArray(sortedIndices.array(), sortedIndices.size());
        out.putByte(fixedOrder ? 1 : 0);
        out.putInts(facingRuns, facingRuns.length);
        out.putLongs(animatedSprites, animatedSprites.length);
    }

    public void decode(Bytes.In in) {
        clear();
        visibility = in.getLong();
        vertexSize = in.getUnsignedByte();
        for (int layer = 0; layer < MeshTables.LAYER_COUNT; layer++) {
            vertexCounts[layer] = in.getInt();
            byte[] bytes = in.getByteArray();
            vertices[layer].putBytes(bytes, 0, bytes.length);
        }
        centroids = in.getFloats();
        translucentQuads = centroids.length / 3;
        intIndices = in.getByte() != 0;
        byte[] indices = in.getByteArray();
        sortedIndices.putBytes(indices, 0, indices.length);
        fixedOrder = in.getByte() != 0;
        int[] runs = in.getInts();
        System.arraycopy(runs, 0, facingRuns, 0, facingRuns.length);
        animatedSprites = in.getLongs();
    }
}
