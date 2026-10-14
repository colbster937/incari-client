package incari.client.render;

import com.google.common.primitives.Floats;
import it.unimi.dsi.fastutil.ints.IntArrays;

/**
 * The order the game draws translucent quads in, farthest first, found by a radix sort (ADR 0027).
 *
 * <p>The game sorts every translucent draw's quads by their distance from the camera, every frame
 * for entities and text (players' skins are drawn translucent), with a stable merge sort through a
 * comparator lambda: in the lobby's profile, with the distances and the index writing around it,
 * about 3.5% of the client's time. This gives the same permutation, ties included: a stable sort of
 * the indices by {@link Float#compare} on the keys, descending, as
 * {@code IntArrays.mergeSort(indices, (a, b) -> Floats.compare(keys[b], keys[a]))} does.
 *
 * <p>A float's bits, with the sign bit flipped for positives and every bit for negatives, order as
 * unsigned integers exactly as {@link Float#compare} orders the floats (-0 below 0, every NaN as one,
 * above infinity, since {@link Float#floatToIntBits} folds them). Inverted, they sort descending; a
 * least-significant-digit radix sort is stable, so equal keys keep their indices' order.
 *
 * <p>Allocates what the sort needs per call, as the game's sort does: the desktop sorts on several
 * threads at once.
 */
public final class QuadSort {

    /** Below this an insertion sort is quicker; it's stable too. */
    private static final int SMALL = 32;

    private static volatile boolean enabled = true;

    private QuadSort() {}

    /** The page's {@code ?sort=game}: the game's merge sort, to compare. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code VertexSorting.byDistance}): sorts {@code indices} (each {@code 0} to
     * {@code keys.length - 1}, in that order) by their keys, largest first, as the game's merge sort
     * does.
     */
    @SuppressWarnings("ReferenceEquality") // which of the two arrays the last pass wrote into
    public static void sortDescending(float[] keys, int[] indices) {
        if (!enabled) {
            IntArrays.mergeSort(indices, (o1, o2) -> Floats.compare(keys[o2], keys[o1]));
            return;
        }
        int n = indices.length;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = ~sortable(keys[indices[i]]);
        }
        if (n < SMALL) {
            insertionSort(order, indices);
            return;
        }
        int[] otherKeys = new int[n];
        int[] otherIndices = new int[n];
        int[] counts = new int[256];
        int[] keysIn = order;
        int[] indicesIn = indices;
        int[] keysOut = otherKeys;
        int[] indicesOut = otherIndices;
        for (int shift = 0; shift < 32; shift += 8) {
            java.util.Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                counts[(keysIn[i] >>> shift) & 0xFF]++;
            }
            if (counts[(keysIn[0] >>> shift) & 0xFF] == n) {
                // Every key has the same byte here: this pass would change nothing.
                continue;
            }
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                int count = counts[b];
                counts[b] = sum;
                sum += count;
            }
            for (int i = 0; i < n; i++) {
                int slot = counts[(keysIn[i] >>> shift) & 0xFF]++;
                keysOut[slot] = keysIn[i];
                indicesOut[slot] = indicesIn[i];
            }
            int[] swapKeys = keysIn;
            keysIn = keysOut;
            keysOut = swapKeys;
            int[] swapIndices = indicesIn;
            indicesIn = indicesOut;
            indicesOut = swapIndices;
        }
        if (indicesIn != indices) {
            System.arraycopy(indicesIn, 0, indices, 0, n);
        }
    }

    /** {@code value}'s bits, made to order as unsigned integers as {@link Float#compare} orders floats. */
    static int sortable(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) | 0x80000000);
    }

    /** A stable insertion sort of {@code indices} by {@code keys}, unsigned, ascending. */
    private static void insertionSort(int[] keys, int[] indices) {
        for (int i = 1; i < keys.length; i++) {
            int key = keys[i];
            int index = indices[i];
            int j = i - 1;
            while (j >= 0 && Integer.compareUnsigned(keys[j], key) > 0) {
                keys[j + 1] = keys[j];
                indices[j + 1] = indices[j];
                j--;
            }
            keys[j + 1] = key;
            indices[j + 1] = index;
        }
    }
}
