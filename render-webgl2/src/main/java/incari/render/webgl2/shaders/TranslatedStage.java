package incari.render.webgl2.shaders;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One shader stage as the build translated it: GLSL ES 3.00 for WebGL2, plus what the device needs
 * to wire it up without reflecting on the source at run time (WebGL has no SPIR-V to reflect on).
 *
 * @param key the stage's {@link ShaderStageKey#asString() key}
 * @param sourceHash {@link SourceHash} of the vanilla source the translation came from
 * @param glsl the GLSL ES 3.00 source, or empty if the stage is unsupported
 * @param attributes vertex shader inputs by name, as the game's vertex formats name their elements
 * @param uniformBlocks the uniform blocks' names, which are the pipeline's uniform names
 * @param samplers the sampler uniforms' names, which are the pipeline's uniform names
 * @param unsupported why WebGL2 can't run this stage yet (for example a texel buffer), or null
 */
public record TranslatedStage(
        String key,
        String sourceHash,
        String glsl,
        List<Attribute> attributes,
        List<String> uniformBlocks,
        List<String> samplers,
        @Nullable String unsupported) {

    public TranslatedStage {
        attributes = List.copyOf(attributes);
        uniformBlocks = List.copyOf(uniformBlocks);
        samplers = List.copyOf(samplers);
    }

    public static TranslatedStage unsupported(String key, String sourceHash, String reason) {
        return new TranslatedStage(key, sourceHash, "", List.of(), List.of(), List.of(), reason);
    }

    public boolean isSupported() {
        return unsupported == null;
    }

    /**
     * A vertex shader input.
     *
     * @param integer whether the shader reads it as an integer ({@code int}, {@code uvec4}...), which
     *     decides between {@code vertexAttribIPointer} and {@code vertexAttribPointer}
     */
    public record Attribute(String name, int location, boolean integer) {}
}
