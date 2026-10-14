package incari.tools.shaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import incari.render.webgl2.shaders.TranslatedStage;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs shaderc and SPIRV-Cross (LWJGL's natives) on small shaders written for the test. */
class TranslationTest {

    private static final String VERTEX = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };
            layout(location = 0) in vec3 Position;
            layout(location = 1) in ivec2 Id;
            layout(location = 2) out vec4 tint;
            void main() {
                gl_Position = ProjMat * vec4(Position, 1.0);
                tint = vec4(float(Id.x));
            }
            """;

    private static final String FRAGMENT = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };
            uniform sampler2D Sampler0;
            layout(location = 2) in vec4 color;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(Sampler0, color.xy) * ProjMat[0][0];
            }
            """;

    private static SpirvCompiler compiler;

    @BeforeAll
    static void start() {
        compiler = new SpirvCompiler();
    }

    @AfterAll
    static void stop() {
        compiler.close();
    }

    @Test
    void translatesToEsWithWebGlsNaming() throws Exception {
        TranslatedStage vertex = translate("vertex test:v", VERTEX, ShaderType.VERTEX);
        TranslatedStage fragment = translate("fragment test:f", FRAGMENT, ShaderType.FRAGMENT);

        assertThat(vertex.glsl()).startsWith("#version 300 es");
        // Varyings meet by name in ES 3.00, so both sides are named after the location they share,
        // even though the game's two stages call it tint and color.
        assertThat(vertex.glsl()).contains("out vec4 incari_io_2;").doesNotContain("tint");
        assertThat(fragment.glsl()).contains("in vec4 incari_io_2;").doesNotContain(" color;");
        // Blocks keep their names (the pipeline binds by them), with one instance name in both stages.
        assertThat(vertex.glsl()).contains("uniform Projection").contains("} incari_ubo_Projection;");
        assertThat(fragment.glsl()).contains("} incari_ubo_Projection;");
        assertThat(vertex.uniformBlocks()).containsExactly("Projection");
        assertThat(fragment.samplers()).containsExactly("Sampler0");
        assertThat(vertex.attributes())
                .containsExactlyInAnyOrder(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("Id", 1, true));
    }

    @Test
    void texelBuffersBecomeTwoDimensionalTextures() throws Exception {
        String source = """
                #version 330
                uniform isamplerBuffer Faces;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    int index = int(gl_FragCoord.x) * 3;
                    fragColor = vec4(texelFetch(Faces, index + 1).r);
                }
                """;

        TranslatedStage stage = translate("fragment test:clouds", source, ShaderType.FRAGMENT);

        assertThat(stage.isSupported()).isTrue();
        assertThat(stage.glsl()).contains("uniform highp isampler2D Faces;").doesNotContain("samplerBuffer");
        assertThat(stage.glsl()).containsPattern("texelFetch\\(Faces, incari_texel_Faces\\(.*\\), 0\\)");
        assertThat(stage.samplers()).containsExactly("Faces");
        assertValidEs(stage.glsl(), "frag");
    }

    @Test
    void fragmentOutputArraysBecomeOutputs() throws Exception {
        String source = """
                #version 330
                layout(location = 0) out vec4 coeff[2];
                void main() {
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 4; j++) {
                            coeff[i][j] = float(i * 4 + j);
                        }
                    }
                }
                """;

        TranslatedStage stage = translate("fragment test:oit", source, ShaderType.FRAGMENT);

        assertThat(stage.glsl())
                .contains("layout(location = 0) out vec4 incari_out_coeff_0;")
                .contains("layout(location = 1) out vec4 incari_out_coeff_1;");
        assertValidEs(stage.glsl(), "frag");
    }

    /** Compiles {@code glsl} with glslang as GLSL ES, where it's installed (CI requires it). */
    private static void assertValidEs(String glsl, String stage) throws Exception {
        java.nio.file.Path validator = GlslangCheck.find();
        if (validator == null) {
            return;
        }
        java.nio.file.Path file = java.nio.file.Files.createTempFile("incari-test", "." + stage);
        try {
            java.nio.file.Files.writeString(file, glsl);
            Process process = new ProcessBuilder(validator.toString(), file.toString())
                    .redirectErrorStream(true)
                    .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output + "\n" + glsl).isZero();
        } finally {
            java.nio.file.Files.delete(file);
        }
    }

    @Test
    void compileErrorsNameTheShader() {
        assertThatThrownBy(() ->
                        translate("vertex test:broken", "#version 330\nvoid main() { nope(); }\n", ShaderType.VERTEX))
                .isInstanceOf(ShaderException.class)
                .hasMessageContaining("shaderc")
                .hasMessageContaining("nope");
    }

    private static TranslatedStage translate(String key, String source, ShaderType type) throws ShaderException {
        int[] spirv = compiler.compile(key, source, type, Map.of());
        return EsslTranslator.translate(key, "0000000000000000", spirv, type);
    }
}
