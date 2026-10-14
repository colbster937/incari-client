package incari.client.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.audio.SoundBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class DecodedSoundBudgetTest {

    private static final Identifier A = Identifier.withDefaultNamespace("sounds/a.ogg");
    private static final Identifier B = Identifier.withDefaultNamespace("sounds/b.ogg");
    private static final Identifier C = Identifier.withDefaultNamespace("sounds/c.ogg");

    private static Map<Identifier, CompletableFuture<SoundBuffer>> cacheOf(Identifier... locations) {
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = new HashMap<>();
        for (Identifier location : locations) {
            cache.put(location, new CompletableFuture<>());
        }
        return cache;
    }

    @Test
    void dropsTheSoundsAskedForLongestAgoPastTheBudget() {
        DecodedSoundBudget budget = new DecodedSoundBudget();
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = cacheOf(A, B, C);
        budget.decoded(A, 100);
        budget.decoded(B, 100);
        budget.decoded(C, 100);
        // A was asked for again, so B is now the oldest.
        budget.used(A);

        budget.trim(cache, C, 250);

        assertThat(cache).containsOnlyKeys(A, C);
        assertThat(budget.total()).isEqualTo(200);
    }

    @Test
    void neverDropsTheSoundBeingAskedFor() {
        DecodedSoundBudget budget = new DecodedSoundBudget();
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = cacheOf(A, B);
        budget.decoded(A, 1000);
        budget.decoded(B, 10);

        budget.trim(cache, A, 100);

        assertThat(cache).containsOnlyKeys(A);
        assertThat(budget.total()).isEqualTo(1000);
    }

    @Test
    void countsASoundDecodedAgainOnce() {
        DecodedSoundBudget budget = new DecodedSoundBudget();
        budget.decoded(A, 100);
        // After a resource reload the game decodes it afresh.
        budget.decoded(A, 120);
        assertThat(budget.total()).isEqualTo(120);
    }

    @Test
    void keepsEverythingWithinTheBudget() {
        DecodedSoundBudget budget = new DecodedSoundBudget();
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = cacheOf(A, B);
        budget.decoded(A, 100);
        budget.decoded(B, 100);

        budget.trim(cache, A, 200);

        assertThat(cache).containsOnlyKeys(A, B);
    }
}
