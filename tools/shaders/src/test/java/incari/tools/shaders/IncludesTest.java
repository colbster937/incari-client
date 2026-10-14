package incari.tools.shaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IncludesTest {

    @Test
    void expandsNestedIncludesInPlace() throws Exception {
        Map<String, String> includes = Map.of(
                "minecraft:a.glsl", "float a;\n#include <minecraft:b.glsl>\n",
                "minecraft:b.glsl", "float b;\n");

        String expanded = Includes.expand("#version 330\n  #include <minecraft:a.glsl>\nvoid main() {}", includes);

        assertThat(expanded).isEqualTo("#version 330\nfloat a;\nfloat b;\n\n\nvoid main() {}\n");
    }

    @Test
    void theNamespaceDefaultsToMinecraft() throws Exception {
        assertThat(Includes.expand("#include \"globals.glsl\"", Map.of("minecraft:globals.glsl", "float g;")))
                .isEqualTo("float g;\n");
    }

    @Test
    void aMissingIncludeIsAnError() {
        assertThatThrownBy(() -> Includes.expand("#include <minecraft:gone.glsl>", Map.of()))
                .isInstanceOf(ShaderException.class)
                .hasMessageContaining("minecraft:gone.glsl");
    }

    @Test
    void aCycleIsAnError() {
        Map<String, String> includes = Map.of("minecraft:self.glsl", "#include <minecraft:self.glsl>");
        assertThatThrownBy(() -> Includes.expand("#include <minecraft:self.glsl>", includes))
                .isInstanceOf(ShaderException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void recognizesGlslangsEsOnlyLimits() {
        String output = """
                ERROR: /tmp/x/s111.frag:113: 'variable indexing fragment shader output array' : not supported with this profile: es
                ERROR: /tmp/x/s111.frag:113: '' : compilation terminated
                """;

        assertThat(GlslangCheck.esLimit(output))
                .isEqualTo(new GlslangCheck.EsLimit("variable indexing fragment shader output array", false));
        assertThat(GlslangCheck.esLimit("ERROR: /tmp/x/s1.vert:3: 'foo' : undeclared identifier"))
                .isNull();
    }
}
