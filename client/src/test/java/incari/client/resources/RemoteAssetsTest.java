package incari.client.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteAssetsTest {

    private static final PreparableReloadListener OTHER =
            (state, background, barrier, main) -> CompletableFuture.completedFuture(null);

    /** Runs what it's given when asked to, as the background pool does between frames. */
    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    @AfterEach
    void removeSource() {
        RemoteAssets.install(null);
    }

    @SuppressWarnings("NullAway") // The language manager only keeps the game to show an error screen.
    private static LanguageManager languages(String selected) {
        return new LanguageManager(null, selected, locale -> {});
    }

    @Test
    void englishIsInTheJar() {
        assertThat(RemoteAssets.assetsFor("en_us")).containsExactly(RemoteAssets.FONT);
        assertThat(RemoteAssets.assetsFor("de_de")).containsExactly(RemoteAssets.FONT, "minecraft/lang/de_de.json");
    }

    @Test
    void aReloadOfTheGamesResourcesFetchesInTheBackgroundAndHoldsBackItsReaders() {
        List<List<String>> fetched = new ArrayList<>();
        RemoteAssets.install(fetched::add);
        Queue background = new Queue();
        LanguageManager languages = languages("fr_fr");

        List<PreparableReloadListener> gated = RemoteAssets.gate(List.of(OTHER, languages), background);
        assertThat(gated.get(0)).isSameAs(OTHER);
        assertThat(gated.get(1)).isInstanceOf(RemoteAssets.Gated.class);
        assertThat(gated.get(1).getName()).isEqualTo(languages.getName());
        assertThat(fetched).isEmpty();

        background.runAll();
        assertThat(fetched).containsExactly(RemoteAssets.assetsFor("fr_fr"));
    }

    @Test
    void aGatedListenerReloadsOnTheGamesThreadOnceTheFetchIsDone() {
        List<String> calls = new ArrayList<>();
        PreparableReloadListener listener = (state, background, barrier, main) -> {
            calls.add("reload");
            return CompletableFuture.completedFuture(null);
        };
        CompletableFuture<Void> fetch = new CompletableFuture<>();
        Queue main = new Queue();

        CompletableFuture<Void> done = new RemoteAssets.Gated(listener, fetch).reload(null, Runnable::run, null, main);
        assertThat(calls).isEmpty();
        fetch.complete(null);
        assertThat(calls).isEmpty();
        main.runAll();
        assertThat(calls).containsExactly("reload");
        assertThat(done).isDone();
    }

    @Test
    void aFailedFetchLeavesTheReloadToGoOn() {
        CompletableFuture<Void> fetched = RemoteAssets.fetch(
                assets -> {
                    throw new IOException("offline");
                },
                List.of(RemoteAssets.FONT),
                Runnable::run);
        assertThat(fetched).isCompleted().isNotCompletedExceptionally();
    }

    @Test
    void otherReloadsAndTheDesktopAreLeftAlone() {
        List<PreparableReloadListener> withLanguages = List.of(languages("de_de"));
        assertThat(RemoteAssets.gate(withLanguages, Runnable::run)).isSameAs(withLanguages);
        RemoteAssets.install(assets -> {});
        List<PreparableReloadListener> others = List.of(OTHER);
        assertThat(RemoteAssets.gate(others, Runnable::run)).isSameAs(others);
    }
}
