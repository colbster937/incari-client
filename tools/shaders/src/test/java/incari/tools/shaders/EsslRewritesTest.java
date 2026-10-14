package incari.tools.shaders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EsslRewritesTest {

    @Test
    void texelFetchesReadTheTwoDimensionalLayout() {
        String glsl = """
                #version 300 es
                #extension GL_OES_texture_buffer : require
                uniform highp isamplerBuffer CloudFaces;
                void main()
                {
                    int a = texelFetch(CloudFaces, (idx * 3) + 1).x;
                    int b = texelFetch(CloudFaces, f(idx, g(2))).x;
                }
                """;

        String rewritten = EsslRewrites.texelBuffers(glsl, List.of("CloudFaces"));

        assertThat(rewritten)
                .doesNotContain("#extension")
                .contains("uniform highp isampler2D CloudFaces;")
                .contains("return ivec2(i & 2047, i >> 11);")
                .contains("texelFetch(CloudFaces, incari_texel_CloudFaces((idx * 3) + 1), 0).x")
                .contains("texelFetch(CloudFaces, incari_texel_CloudFaces(f(idx, g(2))), 0).x");
    }

    @Test
    void shadersWithoutTexelBuffersAreUntouched() {
        String glsl = "#version 300 es\nvoid main()\n{\n}\n";
        assertThat(EsslRewrites.texelBuffers(glsl, List.of())).isSameAs(glsl);
        assertThat(EsslRewrites.fragmentOutputArrays(glsl)).isSameAs(glsl);
    }

    @Test
    void outputArraysAreCopiedToOutputsAfterMain() {
        String glsl = """
                #version 300 es
                layout(location = 0) out highp vec4 coeff[2];
                void main()
                {
                    coeff[i] = vec4(1.0);
                }
                """;

        String rewritten = EsslRewrites.fragmentOutputArrays(glsl);

        assertThat(rewritten)
                .contains("""
                        layout(location = 0) out highp vec4 incari_out_coeff_0;
                        layout(location = 1) out highp vec4 incari_out_coeff_1;
                        highp vec4 coeff[2];""")
                .contains("void incari_main()\n{\n    coeff[i] = vec4(1.0);\n}")
                .endsWith("""
                        void main()
                        {
                            incari_main();
                            incari_out_coeff_0 = coeff[0];
                            incari_out_coeff_1 = coeff[1];
                        }

                        """);
    }
}
