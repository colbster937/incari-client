package incari.render.webgl2.shaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ShaderManifestTest {

    @Test
    void keysDontDependOnTheOrderDefinesWereAddedIn() {
        ShaderDefines one = new ShaderDefines(Map.of("B", "2", "A", "1"), Set.of("Y", "X"));
        ShaderDefines other = ShaderDefines.builder()
                .define("X")
                .define("A", "1")
                .define("Y")
                .define("B", "2")
                .build();
        Identifier id = Identifier.withDefaultNamespace("core/terrain");

        ShaderStageKey key = ShaderStageKey.of(ShaderType.FRAGMENT, id, one);

        assertThat(key).isEqualTo(ShaderStageKey.of(ShaderType.FRAGMENT, id, other));
        assertThat(key.asString()).isEqualTo("fragment minecraft:core/terrain A=1;B=2;X;Y");
    }

    @Test
    void aFlagJoinsTheDefinesInCanonicalOrder() {
        Identifier id = Identifier.withDefaultNamespace("core/terrain");
        ShaderDefines defines = new ShaderDefines(Map.of("A", "1"), Set.of("X", "Z"));
        ShaderDefines withFlag = new ShaderDefines(Map.of("A", "1"), Set.of("X", "Z", "Y"));

        assertThat(ShaderStageKey.of(ShaderType.VERTEX, id, defines).withFlag("Y"))
                .isEqualTo(ShaderStageKey.of(ShaderType.VERTEX, id, withFlag));
        assertThat(ShaderStageKey.of(ShaderType.VERTEX, id, ShaderDefines.EMPTY)
                        .withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE)
                        .asString())
                .isEqualTo("vertex minecraft:core/terrain RENDERPEARL_DEPTH_IS_ZERO_TO_ONE");
    }

    @Test
    void stagesDifferByTypeAndDefines() {
        Identifier id = Identifier.withDefaultNamespace("core/gui");
        assertThat(ShaderStageKey.of(ShaderType.VERTEX, id, ShaderDefines.EMPTY).asString())
                .isEqualTo("vertex minecraft:core/gui ")
                .isNotEqualTo(ShaderStageKey.of(ShaderType.FRAGMENT, id, ShaderDefines.EMPTY)
                        .asString());
    }

    @Test
    void sourceHashIsStableAndSensitive() {
        assertThat(SourceHash.of("")).isEqualTo("cbf29ce484222325");
        assertThat(SourceHash.of("void main() {}")).hasSize(16).isEqualTo(SourceHash.of("void main() {}"));
        assertThat(SourceHash.of("void main() {}")).isNotEqualTo(SourceHash.of("void main(){}"));
    }

    @Test
    void roundTripsThroughJson() {
        TranslatedStage vertex = new TranslatedStage(
                "vertex minecraft:core/gui ",
                SourceHash.of("source"),
                "#version 300 es\nvoid main() {}\n",
                List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("Id", 3, true)),
                List.of("Projection", "DynamicTransforms"),
                List.of("Sampler0"),
                null);
        TranslatedStage clouds =
                TranslatedStage.unsupported("vertex minecraft:core/clouds ", "0123456789abcdef", "texel buffer");

        ShaderManifest parsed = ShaderManifest.parse(new ShaderManifest("26.3", List.of(vertex, clouds)).toJson());

        assertThat(parsed.minecraftVersion()).isEqualTo("26.3");
        assertThat(parsed.stages()).containsExactlyInAnyOrder(vertex, clouds);
        assertThat(parsed.stage(ShaderStageKey.of(
                        ShaderType.VERTEX, Identifier.withDefaultNamespace("core/clouds"), ShaderDefines.EMPTY)))
                .isEqualTo(clouds)
                .satisfies(stage -> assertThat(stage.isSupported()).isFalse());
    }

    @Test
    void refusesAnotherFormat() {
        assertThatThrownBy(() -> ShaderManifest.parse("{\"format\":0,\"minecraft\":\"26.3\",\"stages\":{}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rebuild");
    }
}
