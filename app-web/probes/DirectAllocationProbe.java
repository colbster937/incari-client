import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression probe for tools/teavm-patches' Heap fix: direct buffers of a whole number of
 * 64 KiB pages, allocated once the heap's last record is in use. TeaVM grew the heap by exactly the
 * request, which left the new free record a header too small, and threw OutOfMemoryError with the
 * memory far from full: the game's 256x256 colour maps failed to load as soon as the heap's layout
 * put an allocated record last. Keeps every buffer (half a gigabyte), so each one lands at the end
 * of the heap. Prints "probe passed" when they all fit.
 */
public final class DirectAllocationProbe {

    private DirectAllocationProbe() {}

    public static void main(String[] args) {
        List<ByteBuffer> kept = new ArrayList<>();
        try {
            // Each odd-sized buffer grows the heap by whole pages and leaves the rest of the last
            // page free, a remainder 8 bytes different each time, so one of them fits exactly and
            // leaves the last record allocated; the page-sized buffer after it then needs the fix.
            for (int k = 0; k < 4096; k++) {
                kept.add(ByteBuffer.allocateDirect(65536 - 8 * (k + 1)));
                kept.add(ByteBuffer.allocateDirect(65536));
            }
        } catch (OutOfMemoryError e) {
            System.out.println("probe failed: OutOfMemoryError after " + kept.size() + " buffers");
            return;
        }
        System.out.println("probe passed");
    }
}
