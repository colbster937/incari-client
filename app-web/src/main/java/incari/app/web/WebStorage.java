package incari.app.web;

import incari.platform.api.storage.PlatformStorage;
import org.jspecify.annotations.Nullable;

/** Where the browser build keeps its files: under {@code WebFileSystem.ROOT}, which survives a reload. */
record WebStorage(
        String gameDirectory,
        String assetsDirectory,
        @Nullable String assetIndex) implements PlatformStorage {

    static final WebStorage DEFAULT = new WebStorage("/incari/game", "/incari/assets", null);
}
