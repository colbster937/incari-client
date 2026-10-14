package incari.client.audio;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Where sound files come from when the page has only a placeholder for them. The browser build
 * lists every sound of the asset index in its file system without downloading them (they're
 * hundreds of megabytes); a placeholder is an empty file, and its contents are fetched the first
 * time the sound plays. The entry point installs the source; the desktop has the real files and
 * installs none.
 */
public final class RemoteSounds {

    /** Fetches an asset's contents by its name in the asset index. */
    @FunctionalInterface
    public interface Source {

        /**
         * The contents of {@code assetName} (like {@code minecraft/sounds/random/click.ogg}), or null
         * if it isn't an asset this source has. Waits for the download: only the sound loader's
         * threads call it.
         */
        byte @Nullable [] fetch(String assetName) throws IOException;
    }

    private static volatile @Nullable Source source;

    private RemoteSounds() {}

    /** Installs the source; null removes it. */
    public static void install(@Nullable Source remote) {
        source = remote;
    }

    /** The asset's contents from the installed source, or null if there's none or it doesn't have it. */
    static byte @Nullable [] fetch(String assetName) throws IOException {
        Source current = source;
        return current == null ? null : current.fetch(assetName);
    }
}
