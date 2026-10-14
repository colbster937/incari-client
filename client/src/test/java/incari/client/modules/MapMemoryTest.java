package incari.client.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapMemoryTest {

    @TempDir
    Path gameDirectory;

    @AfterEach
    void reset() {
        MapMemory.close();
        MapMemory.setRemember(true);
    }

    @Test
    void rememberingAgainKeepsWhatTheFileHad() {
        MapMemory.open(gameDirectory, "world/Test");
        MapMemory.record("overworld", 0, 0, colors(0xFF112233));
        MapMemory.close();

        // A visit without remembering makes the region afresh, without its file.
        MapMemory.setRemember(false);
        MapMemory.open(gameDirectory, "world/Test");
        MapMemory.record("overworld", 1, 0, colors(0xFF445566));
        MapMemory.setRemember(true);
        MapMemory.close();

        MapMemory.open(gameDirectory, "world/Test");
        MapMemory.Region region = MapMemory.region("overworld", 0, 0, false);
        assertThat(region).isNotNull();
        assertThat(region.chunk(0, 0)).isNotNull().contains(0xFF112233);
        assertThat(region.chunk(1, 0)).isNotNull().contains(0xFF445566);
    }

    private static int[] colors(int argb) {
        int[] colors = new int[256];
        Arrays.fill(colors, argb);
        return colors;
    }
}
