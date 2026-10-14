package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.online.IncariPlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The tab list's players and which are Incari players, when names repeat (ADR 0058). */
class WorldPlayersTest {

    /** A player in online mode: a version 4 UUID. */
    private static final UUID STEVE = UUID.fromString("5a1d4e2c-7b3f-4c1a-9d2e-0f1b2c3d4e5f");
    /** An NPC by the same name, as Citizens makes them: a version 2 UUID. */
    private static final UUID NPC = UUID.fromString("0f1e2d3c-4b5a-2968-8776-655443322110");

    private static final IncariPlayer INCARI = new IncariPlayer("p1", false, List.of(), true);

    private final WorldPlayers world = new WorldPlayers();

    private WorldPlayers joinedWith(Map<UUID, String> tab) {
        world.join("play.example.com", "Alex");
        world.tabList(tab);
        assertThat(world.takeLookup(0)).containsExactly("Steve");
        world.seen("Steve", INCARI);
        return world;
    }

    @Test
    void anNpcByThePlayersNameGetsNoBadge() {
        joinedWith(Map.of(NPC, "Steve", STEVE, "Steve"));
        assertThat(world.player(STEVE)).isEqualTo(INCARI);
        assertThat(world.player(NPC)).isNull();
        assertThat(world.uuid("p1")).isEqualTo(STEVE);
    }

    @Test
    void anNpcLeavingKeepsThePlayersBadge() {
        joinedWith(Map.of(NPC, "Steve", STEVE, "Steve"));
        world.tabList(Map.of(STEVE, "Steve"));
        assertThat(world.player(STEVE)).isEqualTo(INCARI);
        assertThat(world.takeLookup(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void anNpcArrivingKeepsThePlayersBadge() {
        joinedWith(Map.of(STEVE, "Steve"));
        world.tabList(Map.of(STEVE, "Steve", NPC, "Steve"));
        assertThat(world.player(STEVE)).isEqualTo(INCARI);
        assertThat(world.player(NPC)).isNull();
    }

    @Test
    void thePlayerLeavingTakesTheBadgeFromTheNameAndAsksAgain() {
        joinedWith(Map.of(NPC, "Steve", STEVE, "Steve"));
        world.tabList(Map.of(NPC, "Steve"));
        assertThat(world.player(NPC)).isNull();
        assertThat(world.players()).isEmpty();
        assertThat(world.takeLookup(Long.MAX_VALUE)).containsExactly("Steve");
    }

    @Test
    void theLastOneLeavingForgetsTheName() {
        joinedWith(Map.of(NPC, "Steve", STEVE, "Steve"));
        world.tabList(Map.of());
        assertThat(world.players()).isEmpty();
        assertThat(world.uuid("p1")).isNull();
    }
}
