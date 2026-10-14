package incari.platform.desktop;

import incari.platform.api.storage.PlatformStorage;
import org.jspecify.annotations.Nullable;

/**
 * Game directories on the local file system, as the launcher (or {@code app-desktop}) chose them.
 *
 * @param gameDirectory where options, saves and packs go
 * @param assetsDirectory the launcher-layout assets directory ({@code indexes/}, {@code objects/})
 * @param assetIndex the asset index id, for example "34"
 */
public record DesktopStorage(
        String gameDirectory,
        String assetsDirectory,
        @Nullable String assetIndex) implements PlatformStorage {}
