package incari.render.webgl2;

import incari.render.webgl2.shaders.TranslatedStage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a terrain vertex shader to read Incari's compact terrain vertex (ADR 0023) instead
 * of the game's block vertex. The compact vertex is 16 bytes where the game's is 28: position and
 * light in four unsigned shorts ({@link #POSITION_LIGHT}: x, y and z in 1/2048ths of a block from
 * -8, then block light in the low byte and sky light in the high one), the color as the game has
 * it, and the texture coordinates in two unsigned shorts ({@link #UV}, 1/32768ths of the atlas).
 * The shader's own {@code Position}, {@code UV0} and {@code UV2} inputs become macros that decode
 * them, so the rest of it reads exactly what it read before.
 *
 * <p>Pure text over SPIRV-Cross's output (one input declaration per line), tested without a browser.
 */
final class CompactTerrainRewrite {

    /** The compact format's position-and-light element, as its vertex format names it. */
    static final String POSITION_LIGHT = "IncariPositionLight";

    /** The compact format's texture coordinates element. */
    static final String UV = "IncariUv";

    private CompactTerrainRewrite() {}

    /**
     * {@code stage} reading the compact vertex.
     *
     * @throws IllegalArgumentException if the shader doesn't declare the game's terrain inputs
     */
    static TranslatedStage rewrite(TranslatedStage stage) {
        String glsl = stage.glsl();
        int positionLocation = location(stage, "Position");
        glsl = replaceDeclaration(
                glsl,
                "vec3",
                "Position",
                "layout(location = " + positionLocation + ") in uvec4 " + POSITION_LIGHT + ";\n"
                        + "#define Position (vec3(" + POSITION_LIGHT + ".xyz) * (1.0 / 2048.0) - vec3(8.0))");
        // A variant that doesn't sample the atlas or the light map (the translucency passes' alpha
        // only) has no UV0 or UV2 input: the translation drops what the shader never reads.
        if (has(stage, "UV0")) {
            glsl = replaceDeclaration(
                    glsl,
                    "vec2",
                    "UV0",
                    "layout(location = " + location(stage, "UV0") + ") in uvec2 " + UV + ";\n" + "#define UV0 (vec2("
                            + UV + ") * (1.0 / 32768.0))");
        }
        if (has(stage, "UV2")) {
            glsl = replaceDeclaration(
                    glsl,
                    "ivec2",
                    "UV2",
                    "#define UV2 ivec2(int(" + POSITION_LIGHT + ".w & 255u), int(" + POSITION_LIGHT + ".w >> 8u))");
        }
        List<TranslatedStage.Attribute> attributes = new ArrayList<>();
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            switch (attribute.name()) {
                case "Position" ->
                    attributes.add(new TranslatedStage.Attribute(POSITION_LIGHT, positionLocation, true));
                case "UV0" -> attributes.add(new TranslatedStage.Attribute(UV, attribute.location(), true));
                case "UV2" -> {}
                default -> attributes.add(attribute);
            }
        }
        return new TranslatedStage(
                stage.key(),
                stage.sourceHash(),
                glsl,
                attributes,
                stage.uniformBlocks(),
                stage.samplers(),
                stage.unsupported());
    }

    private static boolean has(TranslatedStage stage, String name) {
        return stage.attributes().stream()
                .anyMatch(attribute -> attribute.name().equals(name));
    }

    private static int location(TranslatedStage stage, String name) {
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            if (attribute.name().equals(name)) {
                return attribute.location();
            }
        }
        throw new IllegalArgumentException("No " + name + " input");
    }

    private static String replaceDeclaration(String glsl, String type, String name, String replacement) {
        Matcher declaration = Pattern.compile("layout\\(location = \\d+\\) in (?:(?:highp|mediump|lowp) )?"
                        + Pattern.quote(type) + " " + Pattern.quote(name) + ";")
                .matcher(glsl);
        if (!declaration.find()) {
            throw new IllegalArgumentException("No " + type + " " + name + " input declaration");
        }
        return declaration.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}
