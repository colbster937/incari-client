package incari.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import incari.render.webgl2.shaders.TranslatedStage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VertexLayoutTest {

    private static final VertexFormat POSITION_COLOR;

    static {
        TestPlatform.install();
        POSITION_COLOR = VertexFormat.builder(0)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .build();
    }

    @Test
    void aMatrixFillsConsecutiveLocations() throws Exception {
        VertexFormat instance = VertexFormat.builder(1)
                .addAttribute("Transform", GpuFormat.RGBA32_FLOAT, 4)
                .build();

        List<VertexLayout.Binding> bindings = VertexLayout.of(List.of(instance), List.of(attribute("Transform", 4)));

        assertThat(bindings).extracting(VertexLayout.Binding::location).containsExactly(4, 5, 6, 7);
        assertThat(bindings).extracting(VertexLayout.Binding::offset).containsExactly(0, 16, 32, 48);
        assertThat(bindings).extracting(VertexLayout.Binding::divisor).containsOnly(1);
    }

    @Test
    void inputsMatchElementsByName() throws Exception {
        List<VertexLayout.Binding> bindings = VertexLayout.of(
                Arrays.asList(POSITION_COLOR, null), List.of(attribute("Color", 1), attribute("Position", 0)));

        assertThat(bindings)
                .containsExactly(
                        new VertexLayout.Binding(0, 0, 0, 16, 0, GlFormats.attribute(GpuFormat.RGB32_FLOAT), false),
                        new VertexLayout.Binding(0, 1, 12, 16, 0, GlFormats.attribute(GpuFormat.RGBA8_UNORM), false));
    }

    @Test
    void elementsTheShaderDoesntReadAreSkipped() throws Exception {
        assertThat(VertexLayout.of(List.of(POSITION_COLOR), List.of(attribute("Position", 0))))
                .extracting(VertexLayout.Binding::location)
                .containsExactly(0);
    }

    @Test
    void anInputWithoutAnElementIsAnError() {
        assertThatThrownBy(() -> VertexLayout.of(List.of(POSITION_COLOR), List.of(attribute("UV0", 2))))
                .isInstanceOf(VertexLayout.MismatchException.class)
                .hasMessageContaining("UV0");
    }

    @Test
    void terrainsInstanceDataFitsARow() throws Exception {
        // The game's CHUNK_DATA_INSTANCED, read by its multi-draw terrain pipelines.
        VertexFormat chunkData = VertexFormat.builder(1)
                .addAttribute("ChunkPosition", GpuFormat.RGB32_SINT)
                .addAttribute("ChunkVisibility", GpuFormat.R32_FLOAT)
                .build();
        List<TranslatedStage.Attribute> inputs = List.of(
                attribute("Position", 0),
                new TranslatedStage.Attribute("ChunkPosition", 4, true),
                attribute("ChunkVisibility", 5));
        List<VertexLayout.Binding> bindings = VertexLayout.of(Arrays.asList(POSITION_COLOR, chunkData), inputs);

        VertexLayout.DrawRowLayout rows = VertexLayout.drawRows(bindings, inputs);

        assertThat(rows).isNotNull();
        assertThat(rows.slot()).isEqualTo(1);
        assertThat(rows.stride()).isEqualTo(16);
        assertThat(rows.inputs())
                .containsExactly(
                        new DrawIdRewrite.Input("ChunkPosition", 0, DrawIdRewrite.Kind.INT),
                        new DrawIdRewrite.Input("ChunkVisibility", 3, DrawIdRewrite.Kind.FLOAT));
        assertThat(rows.attributes()).extracting(VertexLayout.Binding::location).containsExactly(0);
    }

    @Test
    void noRowsWithoutInstanceDataOrWhenItDoesntFit() throws Exception {
        List<TranslatedStage.Attribute> position = List.of(attribute("Position", 0));
        assertThat(VertexLayout.drawRows(VertexLayout.of(List.of(POSITION_COLOR), position), position))
                .isNull();

        VertexFormat matrix = VertexFormat.builder(1)
                .addAttribute("Transform", GpuFormat.RGBA32_FLOAT, 4)
                .build();
        List<TranslatedStage.Attribute> transform = List.of(attribute("Transform", 4));
        assertThat(VertexLayout.drawRows(VertexLayout.of(List.of(matrix), transform), transform))
                .isNull();
    }

    private static TranslatedStage.Attribute attribute(String name, int location) {
        return new TranslatedStage.Attribute(name, location, false);
    }
}
