package incari.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.render.webgl2.shaders.TranslatedStage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticleInstanceRewriteTest {

    // The inputs as SPIRV-Cross declares them in the particle vertex shader.
    private static final String PARTICLE = """
            #version 300 es

            layout(location = 0) in vec3 Position;
            out float incari_io_0;
            layout(location = 1) in vec2 UV0;
            out vec4 incari_io_3;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;

            void main()
            {
                gl_Position = vec4(Position, 1.0);
                incari_io_0 = UV0.x;
                incari_io_3 = Color * float(UV2.x);
            }
            """;

    private static TranslatedStage stage(String glsl) {
        return new TranslatedStage(
                "vertex minecraft:core/particle ",
                "0",
                glsl,
                List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("UV0", 1, false),
                        new TranslatedStage.Attribute("Color", 2, false),
                        new TranslatedStage.Attribute("UV2", 3, true)),
                List.of(),
                List.of("Sampler2"),
                null);
    }

    @Test
    void theQuadIsWorkedOutFromTheInstance() {
        TranslatedStage rewritten = ParticleInstanceRewrite.rewrite(stage(PARTICLE));

        assertThat(rewritten.glsl())
                .contains("layout(location = 0) in vec3 IncariCenter;")
                .contains("layout(location = 4) in vec4 IncariRotation;")
                .contains("layout(location = 5) in float IncariScale;")
                .contains("layout(location = 6) in vec4 IncariUvs;")
                .contains("layout(location = 2) in vec4 IncariColor;")
                .contains("#define Position incari_particle_position()")
                .contains("#define UV0 incari_particle_uv()")
                .contains("#define Color (IncariColor.zyxw)")
                .contains("gl_VertexID & 3")
                .doesNotContain("in vec3 Position;")
                .doesNotContain("in vec2 UV0;")
                .contains("layout(location = 3) in ivec2 UV2;");
        assertThat(rewritten.attributes())
                .containsExactly(
                        new TranslatedStage.Attribute("IncariCenter", 0, false),
                        new TranslatedStage.Attribute("IncariColor", 2, false),
                        new TranslatedStage.Attribute("UV2", 3, true),
                        new TranslatedStage.Attribute("IncariRotation", 4, false),
                        new TranslatedStage.Attribute("IncariScale", 5, false),
                        new TranslatedStage.Attribute("IncariUvs", 6, false));
    }

    @Test
    void anotherShaderIsRefused() {
        String noUv = PARTICLE.replace("layout(location = 1) in vec2 UV0;\n", "");
        assertThatThrownBy(() -> ParticleInstanceRewrite.rewrite(stage(noUv)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UV0");
    }
}
