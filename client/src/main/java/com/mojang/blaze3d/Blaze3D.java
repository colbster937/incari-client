package com.mojang.blaze3d;

import incari.client.platform.ClientPlatform;
import incari.client.world.FolderDownloads;
import java.net.URI;
import java.nio.file.Path;

/** Incari's replacement for Mojang's {@code Blaze3D} helpers (patches/classes.txt), over the platform. */
public class Blaze3D {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private Blaze3D() {}

    /** The debug crash: kills the process the way a native bug would, to test crash handling. */
    public static void youJustLostTheGame() {
        ClientPlatform.get().crashNatively();
    }

    /** Seconds on the platform's monotonic clock. The game's frame timing is built on it. */
    public static double getTime() {
        return ClientPlatform.get().nanoTime() / NANOS_PER_SECOND;
    }

    /** Opens a link in the player's browser, without waiting for it. */
    public static void openUri(final URI uri) {
        ClientPlatform.get().shell().open(uri);
    }

    /**
     * Opens a folder or file in the system's file manager. In the browser a file downloads, a
     * folder the player adds files to (the pack folders) offers the file picker
     * ({@code PlatformShell.openPath}), and any other folder (a world, the backups) downloads as a
     * zip ({@link FolderDownloads}).
     */
    public static void openPath(final Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (FolderDownloads.shouldDownload(absolute)) {
            FolderDownloads.download(absolute);
            return;
        }
        ClientPlatform.get().shell().openPath(absolute.toString());
    }

    /**
     * Opens a folder for the player to put files in: the system's file manager, or in the browser
     * the file picker, whose files reach the game as a drop. Incari's own folders (skins, capes).
     */
    public static void openFolderToAddFiles(final Path folder) {
        ClientPlatform.get()
                .shell()
                .openPath(folder.toAbsolutePath().normalize().toString());
    }
}
