package incari.platform.api.storage;

import org.jspecify.annotations.Nullable;

/**
 * Where the game keeps its files. Paths are strings in the platform's file system: on the desktop
 * real directories, in the browser folders of the page's file system, which OPFS or IndexedDB
 * keeps across reloads (ADR 0011).
 */
public interface PlatformStorage {

    /** The game directory: options, saves, resource packs, screenshots, logs. */
    String gameDirectory();

    /** The directory holding the game's asset index and objects (sounds, languages). */
    String assetsDirectory();

    /** The asset index to load from {@link #assetsDirectory}, or null if assets come bundled. */
    @Nullable
    String assetIndex();

    /**
     * The files under {@code directory}, encoded for another copy of the game's program
     * (ADR 0039), and dropped from this one without telling the store: they're the other copy's
     * until it hands them back ({@link #takeOver}). Files the store keeps in place (a world's region
     * files in the browser, ADR 0041) go as their sizes, once the store has every write to them; the
     * other copy opens them where they are. May wait for the store. Null where the platform's files
     * are its operating system's, shared by every thread (the desktop).
     */
    default byte @Nullable [] handOver(String directory) {
        return null;
    }

    /** Takes back files {@link #handOver} encoded (here or in the other copy), as the store had them. */
    default void takeOver(byte[] files) {}

    /**
     * In a copy of the program: what changed in its files since it was last asked, encoded; null
     * if nothing did, or if the copy sends its changes to the store itself (ADR 0041).
     */
    default byte @Nullable [] takeChanges() {
        return null;
    }

    /** Passes changes another copy of the program made ({@link #takeChanges}) to this one's store. */
    default void storeChanges(byte[] changes) {}
}
