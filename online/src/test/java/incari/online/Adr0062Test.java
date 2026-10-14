package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.online.IncariPlayer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0062's additions: featured servers in hello, usage counts, and the Incari badge. */
class Adr0062Test {

    private static final String PASSWORD = "correct horse battery";
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID STEVE = new UUID(2, 2);

    private final FakeService fake = new FakeService();

    private Client started() {
        Client client = new Client(fake);
        client.service.start();
        return client.settle();
    }

    private Client signedUp(String username) {
        Client client = started();
        Result<List<String>> result = client.await(done -> client.service.signUp(username, PASSWORD, done));
        assertThat(result.ok()).isTrue();
        return client;
    }

    @Test
    void helloReplacesTheFeaturedServersOnlyWhenItNamesThem() {
        assertThat(started().service.featuredServers()).isNull();
        fake.featured = List.of(
                new String[] {"Carrotcraft", "wss://eagler.carrot-craft.org"},
                new String[] {"", "wss://nameless.example"},
                new String[] {"Spaces", "wss://bad address"});
        assertThat(started().service.featuredServers())
                .containsExactly(new OnlineService.Featured("Carrotcraft", "wss://eagler.carrot-craft.org"));
        fake.featured = List.of();
        assertThat(started().service.featuredServers()).isEmpty();
    }

    @Test
    void aCountGoesOutSignedOutAndFailsOffline() {
        Client client = started();
        Result<Void> sent = client.await(done -> client.service.count("AAAAAAAAAAAAAAAAAAAAAA", "0.2.0", "fast", done));
        assertThat(sent.ok()).isTrue();
        assertThat(fake.counts).hasSize(1);
        assertThat(fake.counts.get(0)).containsExactly("AAAAAAAAAAAAAAAAAAAAAA", "0.2.0", "fast");

        Client offline = new Client(fake);
        Result<Void> failed =
                offline.await(done -> offline.service.count("AAAAAAAAAAAAAAAAAAAAAA", "0.2.0", "fast", done));
        assertThat(failed.ok()).isFalse();
        assertThat(fake.counts).hasSize(1);
    }

    @Test
    void theBadgeShowsUntilTheAccountHidesItAndOthersHearOfTheChange() {
        Client alex = signedUp("AlexAccount");
        Client steve = signedUp("SteveAccount");
        assertThat(alex.service.showsBadge()).isTrue();
        Map<UUID, String> tab = Map.of(ALEX, "Alex", STEVE, "Steve");
        alex.service.joined("play.example.com", "Alex");
        alex.service.tabList(tab);
        alex.settle();
        steve.service.joined("play.example.com", "Steve");
        steve.service.tabList(tab);
        steve.settle();
        IncariPlayer seen = steve.service.player(ALEX);
        assertThat(seen).isNotNull();
        assertThat(seen.badge()).isTrue();

        Result<Void> hidden = alex.await(done -> alex.service.setShowBadge(false, done));
        assertThat(hidden.ok()).isTrue();
        assertThat(alex.service.showsBadge()).isFalse();
        steve.settle();
        assertThat(Objects.requireNonNull(steve.service.player(ALEX)).badge()).isFalse();
    }
}
