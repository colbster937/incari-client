package incari.mesher;

import java.util.Arrays;

/**
 * Sorts translucent quads back to front from the camera, as the game does: by the squared distance
 * of each quad's center, farthest first, quads at the same distance keeping their order. The result
 * is an index buffer of two triangles per quad, 16-bit unless the layer has more vertices than
 * that allows.
 *
 * <p>Worker-side, no game objects. Reuses its scratch arrays; hot path.
 */
public final class QuadSorter {

    private float[] keys = new float[256];
    private int[] order = new int[256];
    private int[] scratch = new int[256];

    /** Sorts {@code result}'s translucent quads for a camera at x y z (section-relative). */
    void sortInto(MeshResult result, float cameraX, float cameraY, float cameraZ) {
        int quads = result.translucentQuads;
        sort(result.centroids, quads, cameraX, cameraY, cameraZ);
        int vertices = result.vertexCounts[MeshTables.LAYER_TRANSLUCENT];
        result.intIndices = (vertices & -65536) != 0;
        writeIndices(result.sortedIndices, quads, result.intIndices);
    }

    /**
     * Writes {@code result}'s translucent quads in their {@link FixedOrder}, given each one's facing
     * and plane.
     */
    void fixedInto(MeshResult result, byte[] facings, float[] planes) {
        int quads = result.translucentQuads;
        grow(quads);
        FixedOrder.keys(facings, planes, quads, keys);
        for (int i = 0; i < quads; i++) {
            order[i] = i;
        }
        mergeSort(0, quads);
        int vertices = result.vertexCounts[MeshTables.LAYER_TRANSLUCENT];
        result.intIndices = (vertices & -65536) != 0;
        writeIndices(result.sortedIndices, quads, result.intIndices);
    }

    /**
     * Sorts {@code count} quads by their centers ({@code centroids}, x y z each) and writes the
     * index buffer to {@code out}.
     */
    public void sortInto(
            float[] centroids,
            int count,
            float cameraX,
            float cameraY,
            float cameraZ,
            boolean intIndices,
            Bytes.Out out) {
        sort(centroids, count, cameraX, cameraY, cameraZ);
        writeIndices(out, count, intIndices);
    }

    private void sort(float[] centroids, int count, float cameraX, float cameraY, float cameraZ) {
        grow(count);
        for (int i = 0; i < count; i++) {
            float dx = cameraX - centroids[i * 3];
            float dy = cameraY - centroids[i * 3 + 1];
            float dz = cameraZ - centroids[i * 3 + 2];
            keys[i] = dx * dx + (dy * dy + dz * dz);
            order[i] = i;
        }
        mergeSort(0, count);
    }

    private void grow(int count) {
        if (keys.length < count) {
            int grown = Math.max(count, keys.length * 2);
            keys = new float[grown];
            order = new int[grown];
            scratch = new int[grown];
        }
    }

    /** A stable merge sort of {@link #order}[from, to), farthest (largest key) first. */
    private void mergeSort(int from, int to) {
        int length = to - from;
        if (length < 16) {
            for (int i = from + 1; i < to; i++) {
                int value = order[i];
                int j = i - 1;
                while (j >= from && before(value, order[j])) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = value;
            }
            return;
        }
        int middle = (from + to) >>> 1;
        mergeSort(from, middle);
        mergeSort(middle, to);
        if (!before(order[middle], order[middle - 1])) {
            return;
        }
        System.arraycopy(order, from, scratch, from, length);
        int left = from;
        int right = middle;
        for (int i = from; i < to; i++) {
            if (right >= to || (left < middle && !before(scratch[right], scratch[left]))) {
                order[i] = scratch[left++];
            } else {
                order[i] = scratch[right++];
            }
        }
    }

    /** Whether quad a sorts strictly before quad b: it's farther from the camera. */
    private boolean before(int a, int b) {
        return Float.compare(keys[b], keys[a]) < 0;
    }

    private void writeIndices(Bytes.Out out, int count, boolean intIndices) {
        out.clear();
        int size = intIndices ? 4 : 2;
        int at = out.reserve(count * 6 * size);
        byte[] data = out.array();
        for (int i = 0; i < count; i++) {
            int first = order[i] * 4;
            at = index(data, at, first, intIndices);
            at = index(data, at, first + 1, intIndices);
            at = index(data, at, first + 2, intIndices);
            at = index(data, at, first + 2, intIndices);
            at = index(data, at, first + 3, intIndices);
            at = index(data, at, first, intIndices);
        }
    }

    private static int index(byte[] data, int at, int value, boolean intIndices) {
        if (intIndices) {
            Bytes.putIntAt(data, at, value);
            return at + 4;
        }
        Bytes.putShortAt(data, at, value);
        return at + 2;
    }

    int[] orderForTest(int count) {
        return Arrays.copyOf(order, count);
    }
}
