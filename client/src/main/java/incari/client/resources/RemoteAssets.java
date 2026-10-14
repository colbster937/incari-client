package incari.client.resources;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The asset index's files a resource reload reads, fetched before the listeners that read them start. The game reads its
 * assets directory beside the client jar: the Unicode font and every language but English. The
 * desktop has the whole directory. The browser has only what it fetched, so it installs a {@link
 * Source} that downloads the files a reload is about to read: the font always, and the selected
 * language. Each is fetched once a page load; the service worker keeps them between loads. The
 * title panorama isn't fetched: no screen draws it (menus show {@code MenuBackdrop}'s photo), so
 * the jar's blank stand-ins do.
 */
public final class RemoteAssets {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The Unicode font's definitions and glyph sheets, the default font's fallback. */
    static final String FONT = "minecraft/font/";

    /** Puts asset index files in the assets directory. */
    @FunctionalInterface
    public interface Source {

        /**
         * Downloads each of {@code assets} the directory doesn't have yet and writes it there. Each
         * is a name in the asset index, or a folder (ending in {@code /}) for every name under it; one
         * the index doesn't have is skipped. Waits for the downloads: runs on a background thread.
         */
        void fetch(List<String> assets) throws IOException;
    }

    private static volatile @Nullable Source source;

    private RemoteAssets() {}

    /** Installs the source; null removes it. */
    public static void install(@Nullable Source remote) {
        source = remote;
    }

    /**
     * Hook ({@code ReloadableResourceManager.createReload}, through {@code DeferredModels}): the
     * reload's listeners, those that read these assets held back until they're fetched. A reload of
     * the game's resources (the one with the language manager) fetches them first: the language
     * manager (the language) and the font manager (the Unicode font) start their reloads once the
     * fetch is done; the rest start at once, as they would. Any
     * other reload is left alone. A failed fetch is logged and the reload goes on with what's there,
     * as the game does with a missing file.
     */
    public static List<PreparableReloadListener> gate(List<PreparableReloadListener> listeners, Executor background) {
        Source remote = source;
        if (remote == null) {
            return listeners;
        }
        @Nullable String language = null;
        for (PreparableReloadListener listener : listeners) {
            if (listener instanceof LanguageManager languages) {
                language = languages.getSelected();
            }
        }
        if (language == null) {
            return listeners;
        }
        CompletableFuture<Void> fetched = fetch(remote, assetsFor(language), background);
        List<PreparableReloadListener> gated = new ArrayList<>(listeners.size());
        for (PreparableReloadListener listener : listeners) {
            boolean reads = listener instanceof LanguageManager || listener instanceof FontManager;
            gated.add(reads ? new Gated(listener, fetched) : listener);
        }
        return gated;
    }

    /**
     * A listener whose reload starts once {@code fetched} is done. The reload calls every listener's
     * {@code reload} as it's made, and their preparations start there: holding back the reload's
     * first task holds back only the applies. Its shared state is prepared at once, as the reload asks.
     */
    record Gated(PreparableReloadListener listener, CompletableFuture<Void> fetched)
            implements PreparableReloadListener {

        @Override
        public CompletableFuture<Void> reload(
                SharedState currentReload, Executor taskExecutor, PreparationBarrier barrier, Executor reloadExecutor) {
            // On the game's thread, where the reload calls it.
            return fetched.thenComposeAsync(
                    ignored -> listener.reload(currentReload, taskExecutor, barrier, reloadExecutor), reloadExecutor);
        }

        @Override
        public void prepareSharedState(SharedState currentReload) {
            listener.prepareSharedState(currentReload);
        }

        @Override
        public String getName() {
            return listener.getName();
        }
    }

    /**
     * Fetches {@code assets} on a background thread (the fetch waits, and the render thread mustn't).
     * Completes normally either way: a failure is logged.
     */
    static CompletableFuture<Void> fetch(Source remote, List<String> assets, Executor background) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        remote.fetch(assets);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.warn("Couldn't fetch {} for the reload: {}", assets, e.toString());
                    }
                },
                background);
    }

    /** What a reload in {@code language} reads from the asset index. */
    static List<String> assetsFor(String language) {
        List<String> assets = new ArrayList<>(List.of(FONT));
        // English is in the client jar.
        if (!language.equals("en_us")) {
            assets.add("minecraft/lang/" + language + ".json");
        }
        return assets;
    }
}
