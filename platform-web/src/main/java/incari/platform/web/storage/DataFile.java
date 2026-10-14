package incari.platform.web.storage;

import java.util.Arrays;

/**
 * A file whose bytes live in the tree. Also carries what the {@link Journal} needs between two
 * flushes: which bytes were written, and whether the size changed.
 */
final class DataFile extends FileNode {

    private static final byte[] EMPTY = new byte[0];
    /** How many separate ranges a file keeps between flushes; more merge the closest two. */
    static final int MAX_DIRTY_RANGES = 8;
    /** Writes closer than this make one range: sending the bytes between is cheaper than another op. */
    static final int MERGE_GAP = 512;

    byte[] data;
    int size;

    /**
     * Written since the last flush: {@link #dirtyCount} ranges [from, to), as pairs in order and
     * apart. A region file's write touches its header at the start and a chunk's sectors further
     * on; one range from the first byte to the last would send the store most of the file each
     * time, so writes far apart stay apart (ADR 0041).
     */
    int[] dirty = new int[2 * MAX_DIRTY_RANGES];

    int dirtyCount;
    boolean resized;
    boolean queued;

    DataFile(String name, long lastModified) {
        super(name, lastModified);
        this.data = EMPTY;
    }

    DataFile(String name, long lastModified, byte[] contents) {
        super(name, lastModified);
        this.data = contents;
        this.size = contents.length;
    }

    @Override
    int length() {
        return size;
    }

    @Override
    Bytes contents() {
        return new Bytes(data, size);
    }

    /** Makes room for {@code capacity} bytes; bytes past {@link #size} are always zero. */
    void ensureCapacity(int capacity) {
        if (capacity > data.length) {
            // Grow by half again, as files tend to be written from start to end in small pieces.
            int grown = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(capacity, data.length + (long) data.length / 2));
            data = Arrays.copyOf(data, Math.max(grown, 16));
        }
    }

    /** Sets the size, zeroing what a shrink cut off so a later growth reads zeros there. */
    void setSize(int newSize) {
        if (newSize < size) {
            Arrays.fill(data, newSize, size, (byte) 0);
        } else {
            ensureCapacity(newSize);
        }
        size = newSize;
    }

    /** Notes that [from, to) was written. Hot path (every write): no allocation. */
    void markWritten(int from, int to) {
        if (from >= to) {
            return;
        }
        int[] ranges = dirty;
        int count = dirtyCount;
        // Most writes continue the last range (a file written front to back).
        if (count > 0 && from <= ranges[2 * count - 1] + MERGE_GAP && to >= ranges[2 * count - 2] - MERGE_GAP) {
            merge(count - 1, from, to);
            return;
        }
        // The first range that ends at or after from (less the gap) is where it goes or merges.
        int at = 0;
        while (at < count && ranges[2 * at + 1] + MERGE_GAP < from) {
            at++;
        }
        if (at < count && ranges[2 * at] - MERGE_GAP <= to) {
            merge(at, from, to);
            return;
        }
        if (count == MAX_DIRTY_RANGES) {
            mergeClosest();
            markWritten(from, to);
            return;
        }
        System.arraycopy(ranges, 2 * at, ranges, 2 * at + 2, 2 * (count - at));
        ranges[2 * at] = from;
        ranges[2 * at + 1] = to;
        dirtyCount = count + 1;
    }

    /** Widens range {@code index} to take in [from, to), then takes in the ranges it now reaches. */
    private void merge(int index, int from, int to) {
        int[] ranges = dirty;
        ranges[2 * index] = Math.min(ranges[2 * index], from);
        ranges[2 * index + 1] = Math.max(ranges[2 * index + 1], to);
        int count = dirtyCount;
        // Earlier ranges it reaches back to.
        while (index > 0 && ranges[2 * index - 1] + MERGE_GAP >= ranges[2 * index]) {
            ranges[2 * index - 2] = Math.min(ranges[2 * index - 2], ranges[2 * index]);
            ranges[2 * index - 1] = Math.max(ranges[2 * index - 1], ranges[2 * index + 1]);
            System.arraycopy(ranges, 2 * index + 2, ranges, 2 * index, 2 * (count - index - 1));
            count--;
            index--;
        }
        // Later ranges it reaches forward to.
        while (index + 1 < count && ranges[2 * index + 1] + MERGE_GAP >= ranges[2 * index + 2]) {
            ranges[2 * index + 1] = Math.max(ranges[2 * index + 1], ranges[2 * index + 3]);
            System.arraycopy(ranges, 2 * index + 4, ranges, 2 * index + 2, 2 * (count - index - 2));
            count--;
        }
        dirtyCount = count;
    }

    /** Joins the two neighbouring ranges with the least between them. */
    private void mergeClosest() {
        int[] ranges = dirty;
        int best = 0;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < dirtyCount; i++) {
            int gap = ranges[2 * i + 2] - ranges[2 * i + 1];
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        ranges[2 * best + 1] = ranges[2 * best + 3];
        System.arraycopy(ranges, 2 * best + 4, ranges, 2 * best + 2, 2 * (dirtyCount - best - 2));
        dirtyCount--;
    }

    void clearDirty() {
        dirtyCount = 0;
        resized = false;
        queued = false;
    }
}
