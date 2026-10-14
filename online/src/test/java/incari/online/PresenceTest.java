package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the client tells the service about where the player is, and how often (ADR 0058). */
class PresenceTest {

    private static final String PASSWORD = "correct horse battery";

    private final FakeService fake = new FakeService();

    private Client signedUp(String username) {
        Client client = new Client(fake);
        client.service.start();
        client.settle();
        assertThat(client.<List<String>>await(done -> client.service.signUp(username, PASSWORD, done))
                        .ok())
                .isTrue();
        return client;
    }

    private List<JsonObject> presences() {
        return fake.received.stream()
                .filter(message -> message.get("t").getAsString().equals("presence"))
                .toList();
    }

    @Test
    void aServerSwitchBackToTheSamePlaceSendsNothingMore() {
        Client alex = signedUp("Alex");
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        assertThat(presences()).hasSize(1);

        // A proxy's server switch: out of the world for a moment, then back through the same address.
        alex.service.left();
        alex.settle();
        alex.now += 3_000;
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        alex.now += OnlineService.PRESENCE_GRACE_MILLIS;
        alex.settle();

        assertThat(presences()).hasSize(1);
    }

    @Test
    void leavingClearsThePlaceOnceTheGraceIsOver() {
        Client alex = signedUp("Alex");
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        alex.service.left();
        alex.settle();
        assertThat(presences()).hasSize(1);

        alex.now += OnlineService.PRESENCE_GRACE_MILLIS;
        alex.settle();
        assertThat(presences()).hasSize(2);
        assertThat(presences().get(1).get("serverHash").isJsonNull()).isTrue();
    }

    @Test
    void anotherServerWithinTheGraceReplacesThePlaceAtOnce() {
        Client alex = signedUp("Alex");
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        alex.service.left();
        alex.service.joined("mc.other.net", "AlexMC");
        alex.settle();

        assertThat(presences()).hasSize(2);
        assertThat(presences().get(1).get("serverHash").getAsString()).isEqualTo(ServerHash.of("mc.other.net"));
    }

    @Test
    void aLimitedPresenceGoesAgainLater() {
        Client alex = signedUp("Alex");
        fake.limitNext = "presence";
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        assertThat(presences()).hasSize(1);

        // Not every tick: that would only use up the limit again.
        alex.now += OnlineService.PRESENCE_RETRY_MILLIS - 1;
        alex.settle();
        assertThat(presences()).hasSize(1);

        alex.now += 1;
        alex.settle();
        assertThat(presences()).hasSize(2);
        assertThat(fake.last().serverHash).isEqualTo(ServerHash.of("play.example.com"));
        assertThat(alex.warnings).isEmpty();
    }

    @Test
    void aReconnectSendsThePlaceAgain() {
        Client alex = signedUp("Alex");
        alex.service.joined("play.example.com", "AlexMC");
        alex.settle();
        fake.last().drop();
        alex.settle();
        alex.now += 60_000;
        alex.settle();

        assertThat(presences()).hasSize(2);
        assertThat(fake.last().serverHash).isEqualTo(ServerHash.of("play.example.com"));
    }
}
