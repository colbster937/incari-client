package incari.client.startup;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.client.render.SpriteCache;
import incari.client.render.TextLayouts;
import incari.client.resources.RemoteAssets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Block, item and entity models load after the title screen shows (deferred loading of
 * non-essential assets, ADR 0026). The title screen draws no block, item or entity: it needs the
 * GUI's sprites, the fonts, the panorama and the sounds, but the game loads everything before it
 * shows, and the models were the largest part of that (loading every block state definition and
 * model, then baking them).
 *
 * <p>The game's first resource reload runs without the three listeners that make or use models:
 * the model manager and the entity and block entity render dispatchers (whose renderers are made
 * from the entity models). When it's done (the title screen is up) a second reload runs just those
 * three, handed the first one's atlases (the model manager bakes against the stitched blocks and
 * items atlases, which the first reload already stitched and uploaded). Joining a world, the only
 * way to need a model from the title screen, waits for it ({@link #whenReady}), as does any later
 * reload. Nothing else changes: later reloads (resource packs, F3+T) run every listener at once, as
 * the game does.
 *
 * <p>Only where the entry point turns it on (the browser); the desktop loads as the game does.
 */
public final class DeferredModels {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean enabled;

    // All on the game's main thread: the reloads are made there, and their ends run there.
    private static boolean planned;
    private static @Nullable Wave wave;
    private static final List<Runnable> waiting = new ArrayList<>();

    /** The second reload's makings, from the first. */
    private static final class Wave {
        final ResourceManager resources;
        final List<PreparableReloadListener> listeners;
        final Executor background;
        final Executor main;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        AtlasManager.@Nullable PendingStitchResults atlases;

        Wave(ResourceManager resources, List<PreparableReloadListener> listeners, Executor background, Executor main) {
            this.resources = resources;
            this.listeners = listeners;
            this.background = background;
            this.main = main;
        }
    }

    private DeferredModels() {}

    /** Loads models after the title screen from now on (the browser's entry point). */
    public static void enable() {
        enabled = true;
    }

    /**
     * Hook ({@code ReloadableResourceManager.createReload}): the reload of {@code listeners}, as the
     * game makes it, except that the first one leaves the models for a second, and every later one
     * starts only once the models are loaded.
     */
    public static ReloadInstance createReload(
            ResourceManager resources,
            List<PreparableReloadListener> listeners,
            Executor background,
            Executor main,
            CompletableFuture<Unit> initialTask,
            boolean profiled) {
        // The Unicode font and the language, where the platform fetches them.
        listeners = RemoteAssets.gate(listeners, background);
        // The build's decoded sprites only help the first reload (packs may change after it).
        SpriteCache.reloadStarting();
        // Text laid out with the fonts this reload replaces goes, now and when they're replaced.
        TextLayouts.clear();
        if (enabled && !planned && listeners.stream().anyMatch(listener -> listener instanceof ModelManager)) {
            // The game's first reload of its resources (not a server's of its data).
            planned = true;
            List<PreparableReloadListener> now = new ArrayList<>();
            List<PreparableReloadListener> later = new ArrayList<>();
            boolean atlases = false;
            for (PreparableReloadListener listener : listeners) {
                if (listener instanceof ModelManager
                        || listener instanceof EntityRenderDispatcher
                        || listener instanceof BlockEntityRenderDispatcher) {
                    later.add(listener);
                } else {
                    now.add(listener);
                    if (listener instanceof AtlasManager) {
                        atlases = true;
                    }
                }
            }
            if (atlases && !later.isEmpty()) {
                LOGGER.info(
                        "[Incari] Loading the models after the title screen ({})",
                        later.stream().map(PreparableReloadListener::getName).toList());
                Wave second = new Wave(resources, later, background, main);
                now.add(new KeepAtlases(second));
                wave = second;
                ReloadInstance first =
                        SimpleReloadInstance.create(resources, now, background, main, initialTask, profiled);
                var _ = first.done().whenCompleteAsync((result, failure) -> start(second, failure), main);
                return clearLayoutsWhenDone(first, main);
            }
        }
        Wave pending = wave;
        CompletableFuture<Unit> start = initialTask;
        if (pending != null && !pending.done.isDone()) {
            // A reload over the models' half-done one would race it: this one goes after.
            start = initialTask.thenCombine(pending.done.exceptionally(failure -> null), (unit, ignored) -> unit);
        }
        return clearLayoutsWhenDone(
                SimpleReloadInstance.create(resources, listeners, background, main, start, profiled), main);
    }

    private static ReloadInstance clearLayoutsWhenDone(ReloadInstance reload, Executor main) {
        var _ = reload.done().whenCompleteAsync((result, failure) -> TextLayouts.clear(), main);
        return reload;
    }

    /**
     * Hook ({@code ConnectScreen.startConnecting}, {@code Minecraft.doWorldLoad}): whether
     * {@code action}, which needs the models, has to wait for them. If so it runs once they're
     * loaded, while a loading screen over the current one takes the player's input.
     */
    public static boolean whenReady(Runnable action) {
        Wave pending = wave;
        if (pending == null || pending.done.isDone()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setOverlay(new ModelsOverlay(minecraft));
        waiting.add(action);
        return true;
    }

    /**
     * Whether the entity models are there to draw with: false from the title screen until the
     * second reload ends (a screen that shows a model waits, rather than holding up the start).
     */
    public static boolean ready() {
        Wave pending = wave;
        return pending == null || pending.done.isDone();
    }

    /** The first reload is done: the models' own begins, unless the first failed. */
    private static void start(Wave second, @Nullable Throwable failure) {
        AtlasManager.PendingStitchResults atlases = second.atlases;
        if (failure != null) {
            // The game rolls its packs back and reloads everything, which has the models.
            finish(second, false);
            return;
        }
        if (atlases == null) {
            LOGGER.error("The first reload kept no atlases for the models; reloading everything");
            finish(second, false);
            var _ = Minecraft.getInstance().reloadResourcePacks();
            return;
        }
        List<PreparableReloadListener> listeners = new ArrayList<>();
        listeners.add(new HandOnAtlases(atlases));
        listeners.addAll(second.listeners);
        ReloadInstance reload = SimpleReloadInstance.create(
                second.resources,
                listeners,
                second.background,
                second.main,
                CompletableFuture.completedFuture(Unit.INSTANCE),
                false);
        var _ = reload.done()
                .whenCompleteAsync(
                        (result, error) -> {
                            if (error != null) {
                                LOGGER.error(
                                        "Couldn't load the models after the title screen; reloading everything", error);
                                finish(second, false);
                                var _ = Minecraft.getInstance().reloadResourcePacks();
                            } else {
                                LOGGER.info("[Incari] Models loaded; {} waiting to join a world", waiting.size());
                                finish(second, true);
                            }
                        },
                        second.main);
    }

    private static void finish(Wave second, boolean loaded) {
        second.done.complete(null);
        ClientPlatform.get().profiling().mark("models");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.overlay() instanceof ModelsOverlay) {
            minecraft.gui.setOverlay(null);
        }
        List<Runnable> actions = new ArrayList<>(waiting);
        waiting.clear();
        if (loaded) {
            // A plain loop, not forEach: a Consumer lambda that runs one of these (the join can be
            // ConnectScreen's connect thread, which blocks) made every Consumer.accept a coroutine.
            for (Runnable action : actions) {
                action.run();
            }
        }
    }

    /** Keeps the first reload's atlases for the second: the model manager bakes against them. */
    private static final class KeepAtlases implements PreparableReloadListener {
        private final Wave second;

        KeepAtlases(Wave second) {
            this.second = second;
        }

        @Override
        public void prepareSharedState(SharedState currentReload) {
            // After the atlas manager's own (listeners are prepared in order), so it's there.
            second.atlases = currentReload.get(AtlasManager.PENDING_STITCH);
        }

        @Override
        public CompletableFuture<Void> reload(
                SharedState currentReload, Executor taskExecutor, PreparationBarrier barrier, Executor reloadExecutor) {
            return barrier.wait(Unit.INSTANCE).thenAccept(unit -> {});
        }
    }

    /** Gives the second reload the first one's atlases, finished and uploaded by then. */
    private static final class HandOnAtlases implements PreparableReloadListener {
        private final AtlasManager.PendingStitchResults atlases;

        HandOnAtlases(AtlasManager.PendingStitchResults atlases) {
            this.atlases = atlases;
        }

        @Override
        public void prepareSharedState(SharedState currentReload) {
            currentReload.set(AtlasManager.PENDING_STITCH, atlases);
        }

        @Override
        public CompletableFuture<Void> reload(
                SharedState currentReload, Executor taskExecutor, PreparationBarrier barrier, Executor reloadExecutor) {
            return barrier.wait(Unit.INSTANCE).thenAccept(unit -> {});
        }
    }
}
