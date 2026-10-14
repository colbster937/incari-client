package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.Floats;
import it.unimi.dsi.fastutil.ints.IntArrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link QuadSort} gives the game's merge sort's permutation exactly, ties and odd floats included. */
class QuadSortTest {

    private static final float[] ODD = {
        0.0F,
        -0.0F,
        Float.NaN,
        Float.intBitsToFloat(0x7fc00001),
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        Float.MIN_VALUE,
        -Float.MIN_VALUE,
        Float.MAX_VALUE,
        -Float.MAX_VALUE,
        1.0F,
        -1.0F
    };

    @Test
    void sortsAsTheGameDoes() {
        Random random = new Random(20261001);
        for (int round = 0; round < 400; round++) {
            int n = round < 100 ? round : random.nextInt(5000);
            float[] keys = new float[n];
            for (int i = 0; i < n; i++) {
                keys[i] = switch (random.nextInt(4)) {
                    // Few distinct values: many ties, which the order of the indices must survive.
                    case 0 -> random.nextInt(8);
                    case 1 -> ODD[random.nextInt(ODD.length)];
                    case 2 -> (random.nextFloat() - 0.5F) * 1000.0F;
                    default -> random.nextFloat() * random.nextFloat();
                };
            }
            assertThat(quadSort(keys)).as("round %d, %d keys", round, n).containsExactly(gameSort(keys));
        }
    }

    @Test
    void theGamesOwnSortWhenTurnedOff() {
        float[] keys = {3, 1, 2, 2, Float.NaN, -0.0F, 0.0F};
        QuadSort.setEnabled(false);
        try {
            assertThat(quadSort(keys)).containsExactly(gameSort(keys));
        } finally {
            QuadSort.setEnabled(true);
        }
    }

    private static int[] quadSort(float[] keys) {
        int[] indices = identity(keys.length);
        QuadSort.sortDescending(keys, indices);
        return indices;
    }

    private static int[] gameSort(float[] keys) {
        int[] indices = identity(keys.length);
        IntArrays.mergeSort(indices, (o1, o2) -> Floats.compare(keys[o2], keys[o1]));
        return indices;
    }

    private static int[] identity(int n) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        return indices;
    }
}
