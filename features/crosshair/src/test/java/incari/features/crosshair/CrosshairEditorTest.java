package incari.features.crosshair;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.setting.BooleanSetting;
import incari.framework.setting.GridSetting;
import incari.framework.testing.TestGame;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrosshairEditorTest {

    private final CrosshairEditor module = new CrosshairEditor();
    private final TestGame game = new TestGame(module);

    @Test
    void leavesTheGamesCrosshairWhileOff() {
        assertThat(game.system().events().count(incari.framework.event.Events.CROSSHAIR))
                .isZero();
    }

    @Test
    void drawsTheClassicCrossInScreenPixels() {
        module.setEnabled(true);
        List<String> drawn = game.crosshair().drawn;
        // Four arms of 9 by 2 pixels: at half a GUI unit a pixel, 4.5 by 1 units round the middle.
        assertThat(drawn).hasSize(4).allMatch(line -> line.endsWith("#ffffffff"));
        assertThat(drawn).contains("fill 9x2 at 156,120 #ffffffff");
    }

    @Test
    void presetsSetTheSliders() {
        module.setEnabled(true);
        module.preset.set(CrosshairEditor.Preset.DOT);
        assertThat(module.style.get()).isEqualTo(CrosshairEditor.Style.DOT);
        List<String> drawn = game.crosshair().drawn;
        // The outline's dark square, then the dot.
        assertThat(drawn).containsExactly("fill 6x6 at 159,119 #ff000000", "fill 4x4 at 159,119 #ffffffff");
    }

    @Test
    void aSliderMovedMakesThePresetCustomAndPickingItAgainLoadsIt() {
        module.setEnabled(true);
        assertThat(module.preset.get()).isEqualTo(CrosshairEditor.Preset.CLASSIC);

        module.gap.set(5);
        assertThat(module.preset.get()).isEqualTo(CrosshairEditor.Preset.CUSTOM);
        assertThat(module.gap.get()).isEqualTo(5);

        module.preset.set(CrosshairEditor.Preset.CLASSIC);
        assertThat(module.gap.get()).isZero();
        assertThat(module.preset.get()).isEqualTo(CrosshairEditor.Preset.CLASSIC);
    }

    @Test
    void changesColorOnATarget() {
        module.setEnabled(true);
        ((BooleanSetting) module.settings().get("target-color")).set(true);
        game.onTarget = true;
        assertThat(game.crosshair().drawn).allMatch(line -> line.endsWith("#ffff5555"));
    }

    @Test
    void drawsThePixelGridInRuns() {
        module.setEnabled(true);
        module.style.set(CrosshairEditor.Style.GRID);
        GridSetting grid = (GridSetting) module.settings().get("grid");
        grid.clear();
        grid.set(3, 7, true);
        grid.set(4, 7, true);
        grid.set(5, 7, true);
        grid.set(7, 0, true);
        List<String> drawn = game.crosshair().drawn;
        assertThat(drawn).containsExactly("fill 1x1 at 160,113 #ffffffff", "fill 3x1 at 156,120 #ffffffff");
    }
}
