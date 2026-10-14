package incari.platform.api.profile;

/**
 * Memory in use, as the platform can see it. A value the platform can't measure is -1.
 *
 * @param heapUsed bytes in use on the managed heap (the JVM's heap; in the browser, the page's
 *     JavaScript heap, which holds the WebAssembly GC objects too)
 * @param heapLimit the most the managed heap may grow to
 * @param nativeBytes memory outside the managed heap that the game owns: in the browser, the
 *     WebAssembly linear memory that holds its off-heap buffers (ADR 0008)
 * @param collections garbage collections so far; in the browser an estimate (drops in the heap
 *     between frames), since a page can't observe collections
 */
public record MemoryUsage(long heapUsed, long heapLimit, long nativeBytes, long collections) {

    /** Nothing known. */
    public static final MemoryUsage UNKNOWN = new MemoryUsage(-1, -1, -1, -1);
}
