package incari.client.world;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Saved chunks deflated at zlib's fastest level instead of its default. In the browser deflate is
 * jzlib, plain Java compiled to WebAssembly, and its default level (6) was 6% of singleplayer's
 * server worker in the development desktop's fly-through. Level 1 deflates a chunk about 2.7 times
 * faster for about a fifth more bytes (2.4 against 2.0 KB a chunk, measured on the worldgen
 * comparison's region files), and a region file stores a chunk in whole 4 KiB sectors either way,
 * so the files barely grow. Any reader, the game's included, inflates both alike.
 *
 * <p>Region files only ({@code RegionFileVersion}'s deflate); the game's other compressed files are
 * small and written seldom.
 */
public final class ChunkCompression {

    private static boolean enabled;

    private ChunkCompression() {}

    /** Off unless the page turns it on ({@code ?deflate=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code RegionFileVersion}'s deflate): the stream a chunk is written through. */
    public static OutputStream deflate(OutputStream out) {
        if (!enabled) {
            return new DeflaterOutputStream(out);
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        return new DeflaterOutputStream(out, deflater) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    // A deflater handed in isn't ended by the stream, as the stream's own is.
                    deflater.end();
                }
            }
        };
    }
}
