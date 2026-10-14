package incari.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DrawIdRewriteTest {

    // The shape SPIRV-Cross gives the multi-draw terrain vertex shader.
    private static final String TERRAIN = """
            #version 300 es

            layout(std140) uniform Projection
            {
                mat4 ProjMat;
            } incari_ubo_Projection;

            layout(location = 0) in vec3 Position;
            layout(location = 4) in ivec3 ChunkPosition;
            out float incari_io_0;
            layout(location = 5) in float ChunkVisibility;

            void main()
            {
                vec3 pos = Position + vec3(ChunkPosition);
                incari_io_0 = ChunkVisibility;
            }
            """;

    @Test
    void instanceInputsReadTheSubDrawsRow() {
        String rewritten = DrawIdRewrite.rewrite(
                TERRAIN,
                List.of(
                        new DrawIdRewrite.Input("ChunkPosition", 0, DrawIdRewrite.Kind.INT),
                        new DrawIdRewrite.Input("ChunkVisibility", 3, DrawIdRewrite.Kind.FLOAT)));

        assertThat(rewritten)
                .startsWith("""
                        #version 300 es
                        #extension GL_ANGLE_multi_draw : require
                        layout(std140) uniform IncariDrawRows
                        {
                            highp ivec4 incari_draw_rows[1024];
                        };
                        """)
                .contains("#define ChunkPosition (ivec3(incari_draw_rows[gl_DrawID].xyz))")
                .contains("#define ChunkVisibility (float(intBitsToFloat(incari_draw_rows[gl_DrawID].w)))")
                .contains("layout(location = 0) in vec3 Position;")
                .doesNotContain("in ivec3 ChunkPosition")
                .doesNotContain("in float ChunkVisibility");
    }

    @Test
    void unsignedInputsConvert() {
        String glsl = "#version 300 es\nlayout(location = 2) in highp uvec2 Pair;\nvoid main() {}\n";

        String rewritten =
                DrawIdRewrite.rewrite(glsl, List.of(new DrawIdRewrite.Input("Pair", 1, DrawIdRewrite.Kind.UINT)));

        assertThat(rewritten).contains("#define Pair (uvec2(uvec2(incari_draw_rows[gl_DrawID].yz)))");
    }

    @Test
    void refusesWhatDoesntFitOrIsntThere() {
        assertThatThrownBy(() -> DrawIdRewrite.rewrite(
                        TERRAIN, List.of(new DrawIdRewrite.Input("ChunkPosition", 2, DrawIdRewrite.Kind.INT))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrawIdRewrite.rewrite(
                        TERRAIN, List.of(new DrawIdRewrite.Input("Missing", 0, DrawIdRewrite.Kind.INT))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
