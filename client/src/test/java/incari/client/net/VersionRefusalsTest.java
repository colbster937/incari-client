package incari.client.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VersionRefusalsTest {

    private static final int GAME = 777;
    private static final Map<String, Integer> NAMES =
            Map.of("1.8.8", 47, "1.8.9", 47, "1.8.x", 47, "1.12.2", 340, "1.20.4", 765, "1.21.x", 774, "26.1", 775);

    private static int next(String message, int tried, Set<Integer> attempted) {
        return VersionRefusals.nextProtocol(
                message, tried, attempted, name -> NAMES.getOrDefault(name, -1), protocol -> protocol >= 47);
    }

    @Test
    void readsTheVersionASpigotServerIsStillOn() {
        assertThat(next("Outdated server! I'm still on 1.8.8", GAME, Set.of())).isEqualTo(47);
    }

    @Test
    void readsItThroughBungeeCordsKick() {
        assertThat(next("Kicked whilst connecting to lobby: Outdated server! I'm still on 1.8.8", GAME, Set.of()))
                .isEqualTo(47);
    }

    @Test
    void takesTheNewestOfAProxysRangeFirst() {
        // BungeeCord names the range it speaks; its backend may be anywhere in it.
        assertThat(next("Outdated server! I'm still on 1.8.x-1.21.x", GAME, Set.of()))
                .isEqualTo(774);
        // Then the backend's own refusal takes it the rest of the way.
        assertThat(next("Outdated server! I'm still on 1.8.8", 774, Set.of(GAME)))
                .isEqualTo(47);
    }

    @Test
    void readsModernVanillasTranslatedRefusal() {
        assertThat(next("Incompatible client! Please use 1.20.4", GAME, Set.of()))
                .isEqualTo(765);
        // A translation the client has no text for: its key, then its argument.
        assertThat(next("multiplayer.disconnect.outdated_client 1.12.2", GAME, Set.of()))
                .isEqualTo(340);
    }

    @Test
    void readsAnEaglerVersionMismatch() {
        assertThat(next("Unsupported client version (the server takes Minecraft protocols 47 to 340)", GAME, Set.of()))
                .isEqualTo(340);
    }

    @Test
    void neverOffersAVersionAlreadyTried() {
        assertThat(next("Outdated server! I'm still on 1.8.8", 340, Set.of(GAME, 47)))
                .isEqualTo(-1);
    }

    @Test
    void leavesOtherKicksAlone() {
        assertThat(next("You are banned from this server until 1.8.2027", GAME, Set.of()))
                .isEqualTo(-1);
        assertThat(next("Server closed", GAME, Set.of())).isEqualTo(-1);
    }

    @Test
    void offersANewerVersionOnlyWhenNothingOlderIsNamed() {
        assertThat(next("Outdated client! Please use 26.1", 765, Set.of())).isEqualTo(775);
    }
}
