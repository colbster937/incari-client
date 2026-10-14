package incari.framework.waypoint;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.config.ConfigStorage;
import incari.framework.hud.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WaypointsTest {

    private final Map<String, String> files = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final ConfigStorage storage = new ConfigStorage() {
        @Override
        public @Nullable String read(String path) {
            return files.get(path);
        }

        @Override
        public void write(String path, String text) {
            files.put(path, text);
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }
    };
    private final Waypoints waypoints = new Waypoints(storage, problems::add);

    private static Waypoint base() {
        return new Waypoint("Base", 120, 64, -48, "minecraft:overworld", 0x5B8CFF, Shape.STAR);
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            waypoints.tick();
        }
    }

    @Test
    void aDeletedWorldsWaypointsDontComeBackInANewOneOfItsName() {
        waypoints.open("world/New World");
        waypoints.add(base());
        waypoints.close();
        assertThat(files).isNotEmpty();

        waypoints.delete("world/New World");
        waypoints.open("world/New World");
        assertThat(waypoints.all()).isEmpty();
        assertThat(files).isEmpty();
    }

    @Test
    void nothingIsAddedOutsideAWorld() {
        waypoints.add(base());
        assertThat(waypoints.all()).isEmpty();
    }

    @Test
    void savedASecondAfterTheLastChangeAndBackWhenTheWorldIsJoinedAgain() {
        waypoints.open("server/play.example.net");
        Waypoint base = base();
        waypoints.add(base);
        ticks(Waypoints.SAVE_DELAY_TICKS - 1);
        assertThat(files).isEmpty();
        base.setBeam(false);
        ticks(Waypoints.SAVE_DELAY_TICKS - 1);
        assertThat(files).isEmpty();
        ticks(1);
        assertThat(files).hasSize(1);

        waypoints.close();
        assertThat(waypoints.all()).isEmpty();
        waypoints.open("server/play.example.net");
        assertThat(waypoints.all()).singleElement().satisfies(back -> {
            assertThat(back.name()).isEqualTo("Base");
            assertThat(back.x()).isEqualTo(120);
            assertThat(back.y()).isEqualTo(64);
            assertThat(back.z()).isEqualTo(-48);
            assertThat(back.rgb()).isEqualTo(0x5B8CFF);
            assertThat(back.icon()).isEqualTo(Shape.STAR);
            assertThat(back.hasBeam()).isFalse();
            assertThat(back.isVisible()).isTrue();
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void eachWorldHasItsOwn() {
        waypoints.open("server/a.example.net");
        waypoints.add(base());
        waypoints.open("world/New World");
        assertThat(waypoints.all()).isEmpty();
        waypoints.open("server/a.example.net");
        assertThat(waypoints.all()).hasSize(1);
    }

    @Test
    void closingSavesAtOnce() {
        waypoints.open("world/Test");
        waypoints.add(base());
        waypoints.close();
        assertThat(files).hasSize(1);
    }

    @Test
    void removingTheLastDeletesTheFile() {
        waypoints.open("world/Test");
        Waypoint base = base();
        waypoints.add(base);
        waypoints.flush();
        waypoints.remove(base);
        waypoints.flush();
        assertThat(files).isEmpty();
    }

    @Test
    void anEditedCopyIsTakenAsOneChange() {
        waypoints.open("world/Test");
        Waypoint base = base();
        waypoints.add(base);
        long before = waypoints.revision();
        Waypoint copy = base.copy();
        copy.setName("  Home  ");
        copy.setIcon(Shape.HEART);
        assertThat(waypoints.revision()).isEqualTo(before);
        base.set(copy);
        assertThat(waypoints.revision()).isEqualTo(before + 1);
        assertThat(base.name()).isEqualTo("Home");
        assertThat(base.icon()).isEqualTo(Shape.HEART);
    }

    @Test
    void namesAreCut() {
        assertThat(new Waypoint("x".repeat(80), 0, 0, 0, "d", 0, Shape.CIRCLE).name())
                .hasSize(Waypoint.MAX_NAME);
    }

    @Test
    void aBrokenEntryIsLeftOutAndTheRestRead() {
        waypoints.open("world/Test");
        waypoints.close();
        files.put(Waypoints.path("world/Test"), """
                {"format": 1, "waypoints": [
                  {"name": "No position"},
                  {"name": "Odd", "x": 1, "y": 2, "z": 3, "color": "blue", "icon": "zigzag", "visible": "yes"}
                ]}
                """);
        waypoints.open("world/Test");
        assertThat(waypoints.all()).singleElement().satisfies(odd -> {
            assertThat(odd.name()).isEqualTo("Odd");
            assertThat(odd.icon()).isEqualTo(Shape.CIRCLE);
            assertThat(odd.rgb()).isEqualTo(Waypoint.COLORS.get(0));
            assertThat(odd.isVisible()).isTrue();
            assertThat(odd.dimension()).isEqualTo("minecraft:overworld");
        });
        assertThat(problems).hasSize(1);
    }

    @Test
    void anUnreadableFileReadsAsNone() {
        files.put(Waypoints.path("world/Test"), "{not json");
        waypoints.open("world/Test");
        assertThat(waypoints.all()).isEmpty();
        assertThat(problems).hasSize(1);
        // Not overwritten until something changes.
        waypoints.close();
        assertThat(files.get(Waypoints.path("world/Test"))).isEqualTo("{not json");
    }

    @Test
    void fileNamesAreSafeAndApart() {
        assertThat(Waypoints.path("server/play.example.net:25565"))
                .startsWith("waypoints/server_play.example.net_25565-")
                .endsWith(".json");
        assertThat(Waypoints.path("world/a:b")).isNotEqualTo(Waypoints.path("world/a_b"));
    }

    @Test
    void theDeathIsFound() {
        waypoints.open("world/Test");
        waypoints.add(base());
        assertThat(waypoints.death()).isNull();
        Waypoint death = new Waypoint("Death", 0, 70, 0, "minecraft:overworld", 0xFF0000, Shape.CROSS);
        death.setDeath(true);
        waypoints.add(death);
        assertThat(waypoints.death()).isSameAs(death);
    }
}
