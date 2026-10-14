package incari.tools.copycheck;

import java.util.Arrays;
import java.util.List;

/**
 * Hashes of every run of {@code window} consecutive tokens in the vendor tree, sorted for
 * binary search.
 *
 * <p>A primitive sorted array rather than a hash set: the vendor tree has millions of windows,
 * and boxed longs would need several times the memory.
 */
final class WindowIndex {

    private final int window;
    private long[] hashes = new long[1 << 20];
    private int size;
    private boolean sealed;

    WindowIndex(int window) {
        this.window = window;
    }

    int window() {
        return window;
    }

    void addFile(List<JavaTokens.Token> tokens) {
        if (sealed) {
            throw new IllegalStateException("index is sealed");
        }
        for (long hash : windowHashes(tokens, window)) {
            if (size == hashes.length) {
                hashes = Arrays.copyOf(hashes, size * 2);
            }
            hashes[size++] = hash;
        }
    }

    /** Sorts the hashes. Call once, after the last {@link #addFile}. */
    void seal() {
        hashes = Arrays.copyOf(hashes, size);
        Arrays.sort(hashes);
        sealed = true;
    }

    boolean contains(long hash) {
        if (!sealed) {
            throw new IllegalStateException("seal() the index first");
        }
        return Arrays.binarySearch(hashes, hash) >= 0;
    }

    /**
     * One hash per window: {@code result[i]} covers tokens {@code i .. i + window - 1}. Each
     * window is hashed independently, so equal windows get equal hashes wherever they occur.
     */
    static long[] windowHashes(List<JavaTokens.Token> tokens, int window) {
        int count = tokens.size() - window + 1;
        if (count <= 0) {
            return new long[0];
        }
        long[] tokenHashes = new long[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            tokenHashes[i] = mix(tokens.get(i).text().hashCode());
        }
        long[] result = new long[count];
        for (int start = 0; start < count; start++) {
            long hash = 0x9E3779B97F4A7C15L;
            for (int j = start; j < start + window; j++) {
                hash = (hash ^ tokenHashes[j]) * 0x100000001B3L;
                hash = Long.rotateLeft(hash, 27);
            }
            result[start] = hash;
        }
        return result;
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
