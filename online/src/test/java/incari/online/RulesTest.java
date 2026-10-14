package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Back-off, the credential rules and the world's lookup queue, on their own. */
class RulesTest {

    @Test
    void backoffDoublesToFiveMinutesAndStartsOverAfterAMinuteUp() {
        Backoff backoff = new Backoff(() -> 0.999_999);
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            waits.add(backoff.failed(0));
        }
        assertThat(waits.get(0)).isEqualTo(999);
        assertThat(waits.get(1)).isEqualTo(1_999);
        assertThat(waits.get(8)).isEqualTo(255_999);
        assertThat(waits.get(9)).isEqualTo(Backoff.MAX_MILLIS - 1);
        assertThat(waits.get(11)).isEqualTo(Backoff.MAX_MILLIS - 1);
        backoff.connected(0);
        assertThat(backoff.failed(Backoff.STEADY_MILLIS - 1)).isEqualTo(Backoff.MAX_MILLIS - 1);
        backoff.connected(0);
        assertThat(backoff.failed(Backoff.STEADY_MILLIS)).isEqualTo(999);
    }

    @Test
    void backoffWaitsAtLeastHalf() {
        assertThat(new Backoff(() -> 0).failed(0)).isEqualTo(500);
    }

    @Test
    void usernames() {
        assertThat(Credentials.usernameProblem("Frost_1")).isNull();
        assertThat(Credentials.usernameProblem("ab")).isNotNull();
        assertThat(Credentials.usernameProblem("a".repeat(17))).isNotNull();
        assertThat(Credentials.usernameProblem("frost-1")).isNotNull();
        assertThat(Credentials.usernameProblem("frøst")).isNotNull();
    }

    @Test
    void passwords() {
        assertThat(Credentials.passwordProblem("correct horse battery", "Frost"))
                .isNull();
        assertThat(Credentials.passwordProblem("short", "Frost")).contains("At least");
        assertThat(Credentials.passwordProblem("Minecraft123", "Frost")).contains("common");
        assertThat(Credentials.passwordProblem("aaaaaaaaaaaa", "Frost")).contains("common");
        assertThat(Credentials.passwordProblem("ababababab", "Frost")).contains("common");
        assertThat(Credentials.passwordProblem("abcdefghijklm", "Frost")).contains("common");
        assertThat(Credentials.passwordProblem("9876543210", "Frost")).contains("common");
        assertThat(Credentials.passwordProblem("my frost password", "Frost")).contains("username");
    }

    @Test
    void lookupsAreBatchedPacedAndAskedOnce() {
        WorldPlayers world = new WorldPlayers();
        UUID me = new UUID(0, 0);
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        world.join("play.example.com", "Me");
        world.tabList(Map.of(me, "Me", a, "A"));
        assertThat(world.takeLookup(0)).containsExactly("A");
        world.tabList(Map.of(me, "Me", a, "A", b, "B"));
        assertThat(world.takeLookup(1)).isEmpty();
        assertThat(world.takeLookup(WorldPlayers.LOOKUP_INTERVAL_MILLIS)).containsExactly("B");
        // Nobody new: nothing to ask.
        assertThat(world.takeLookup(10 * WorldPlayers.LOOKUP_INTERVAL_MILLIS)).isEmpty();
    }

    @Test
    void lateAnswersForPlayersWhoLeftAreDropped() {
        WorldPlayers world = new WorldPlayers();
        UUID a = new UUID(0, 1);
        world.join("play.example.com", "Me");
        world.tabList(Map.of(a, "A"));
        world.tabList(Map.of());
        world.seen("A", new incari.framework.online.IncariPlayer("p", false, List.of()));
        assertThat(world.player(a)).isNull();
        assertThat(world.players()).isEmpty();
    }

    @Test
    void aLargeTabListIsAskedInParts() {
        WorldPlayers world = new WorldPlayers();
        world.join("play.example.com", "Me");
        java.util.HashMap<UUID, String> tab = new java.util.HashMap<>();
        for (int i = 0; i < 300; i++) {
            tab.put(new UUID(1, i), "P" + i);
        }
        world.tabList(tab);
        assertThat(world.takeLookup(0)).hasSize(WorldPlayers.MAX_LOOKUP);
        assertThat(world.takeLookup(WorldPlayers.LOOKUP_INTERVAL_MILLIS)).hasSize(300 - WorldPlayers.MAX_LOOKUP);
    }
}
