package incari.app.web;

/**
 * Library settings for the page, set as system properties before the libraries read them (they
 * read them once, in static initializers).
 *
 * <p>Netty's buffers (ADR 0008):
 *
 * <ul>
 *   <li>{@code io.netty.allocator.type=unpooled}: Netty 4.2's default adaptive allocator can't
 *       start without method handles ({@code ConcurrentSkipListIntObjMultimap} looks up its own
 *       fallback through one). Unpooled heap buffers are plain {@code byte[]}, which WebAssembly GC
 *       allocates cheaply, and a page with one thread gains little from pooling.
 *   <li>{@code io.netty.noUnsafe=true}: there is no {@code Unsafe}; saying so skips the reflective
 *       probing and the warning about it. With no cleaner, Netty prefers heap buffers; a direct
 *       buffer, where a caller asks for one, is TeaVM's, in the module's linear memory.
 *   <li>{@code io.netty.leakDetection.level=disabled}: leak records capture stack traces and rely
 *       on reference queues, costly here; leaks are found on the desktop build.
 *   <li>{@code io.netty.machineId} and {@code io.netty.processId}: what Netty's channel ids
 *       ({@code DefaultChannelId}) are made from, otherwise found from the network interfaces' MAC
 *       addresses and the process handle, neither of which a page has. Singleplayer's in-memory
 *       channels are the first to use them. A fixed machine and process are fine: the ids only
 *       have to differ within the page, which the counter and the time in each id see to.
 * </ul>
 *
 * <p>JOML: {@code joml.format=false}. JOML's {@code Options} builds a {@code DecimalFormat} from a
 * pattern TeaVM's parser rejects (a leading space), which failed the first {@code org.joml.Math}
 * call (vertex sorting by distance); without it JOML formats with the locale's number format.
 */
final class LibraryDefaults {

    private LibraryDefaults() {}

    static void apply() {
        setDefault("io.netty.allocator.type", "unpooled");
        setDefault("io.netty.noUnsafe", "true");
        setDefault("io.netty.leakDetection.level", "disabled");
        setDefault("io.netty.machineId", "00:00:00:00:00:00:00:01");
        setDefault("io.netty.processId", "1");
        setDefault("joml.format", "false");
    }

    /** Keeps a value set before, so a development page can still choose another. */
    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
