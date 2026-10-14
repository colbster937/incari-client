package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneScriptTest {

    @Test
    void readsSetupScenesAndThreshold() {
        SceneScript script = SceneScript.parse(
                List.of(
                        "# Scenes",
                        "threshold 0.9",
                        "setup",
                        "    rcon:tick freeze",
                        "    key:F1",
                        "",
                        "scene terrain  Overworld terrain at noon",
                        "    rcon:tp IncariShot 10 90 10 45 20",
                        "    settle",
                        "    shot:terrain",
                        "scene gui",
                        "    key:KeyE",
                        "    shot:gui_inventory",
                        "    shot:gui_again"),
                "scenes.txt");
        assertThat(script.threshold()).isEqualTo(0.9);
        assertThat(script.setup())
                .containsExactly(new SceneScript.Step("rcon", "tick freeze", 4), new SceneScript.Step("key", "F1", 5));
        assertThat(script.scenes()).hasSize(2);
        assertThat(script.scenes().getFirst().name()).isEqualTo("terrain");
        assertThat(script.scenes().getFirst().description()).isEqualTo("Overworld terrain at noon");
        assertThat(script.scenes().getFirst().steps()).hasSize(3);
        assertThat(script.scenes().get(1).shots()).containsExactly("gui_inventory", "gui_again");
    }

    @Test
    void refusesMistakes() {
        assertThatThrownBy(() -> SceneScript.parse(List.of("setup", "    rcon:x"), "s"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("threshold");
        assertThatThrownBy(() -> SceneScript.parse(List.of("threshold 0.9", "scene a", "    jump:1"), "s"))
                .hasMessageContaining("unknown step");
        assertThatThrownBy(() -> SceneScript.parse(
                        List.of("threshold 0.9", "scene a", "    shot:x", "scene b", "    shot:x"), "s"))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> SceneScript.parse(List.of("threshold 0.9", "scene a", "scene a"), "s"))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> SceneScript.parse(List.of("threshold 0.9", "setup", "    shot:x"), "s"))
                .hasMessageContaining("setup takes no screenshots");
    }
}
