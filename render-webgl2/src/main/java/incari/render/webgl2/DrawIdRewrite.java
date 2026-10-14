package incari.render.webgl2;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a vertex shader's per-instance inputs to read a row of a uniform block by
 * {@code gl_DrawID} instead (ADR 0022). That's what lets one {@code WEBGL_multi_draw} call
 * draw many terrain sections: the game's multi-draw terrain pipelines read each section's position
 * and fade from per-instance attributes, picked by an indirect draw's first instance, which WebGL2
 * has no way to offset within a multi-draw. The backend copies each draw's instance data into row
 * {@code i} of {@link #BLOCK} for sub-draw {@code i} instead.
 *
 * <p>Pure text over SPIRV-Cross's output, whose shape is fixed (one input declaration per line,
 * {@code layout(location = N) in type name;}), so it's tested without a browser.
 */
final class DrawIdRewrite {

    /** The uniform block holding one row per sub-draw. */
    static final String BLOCK = "IncariDrawRows";

    /** Rows in the block: 1024 rows of 16 bytes is WebGL2's smallest allowed maximum block size. */
    static final int ROWS = 1024;

    /** Bytes per row: one {@code ivec4}. */
    static final int ROW_BYTES = 16;

    /**
     * One per-instance input.
     *
     * @param word where its first component is in the row, in 32-bit words
     * @param kind how its words read: as {@link Kind#INT}, {@link Kind#UINT} or {@link Kind#FLOAT}
     */
    record Input(String name, int word, Kind kind) {}

    /** How an input's 32-bit words are read. */
    enum Kind {
        INT,
        UINT,
        FLOAT
    }

    private static final Pattern VERSION = Pattern.compile("(?m)^#version 300 es\\n");

    private DrawIdRewrite() {}

    /**
     * {@code glsl} with each input in {@code inputs} read from the row of the current sub-draw.
     *
     * @throws IllegalArgumentException if an input isn't declared as expected, or doesn't fit in a row
     */
    static String rewrite(String glsl, List<Input> inputs) {
        Matcher version = VERSION.matcher(glsl);
        if (!version.find()) {
            throw new IllegalArgumentException("Not GLSL ES 3.00");
        }
        String result = glsl;
        for (Input input : inputs) {
            Matcher declaration = Pattern.compile("layout\\(location = \\d+\\) in (?:(?:highp|mediump|lowp) )?(\\w+) "
                            + Pattern.quote(input.name()) + ";")
                    .matcher(result);
            if (!declaration.find()) {
                throw new IllegalArgumentException("No input declaration of " + input.name());
            }
            String type = declaration.group(1);
            int components = components(type);
            if (input.word() < 0 || input.word() + components > 4) {
                throw new IllegalArgumentException(input.name() + " doesn't fit in a row");
            }
            String words = "incari_draw_rows[gl_DrawID]." + "xyzw".substring(input.word(), input.word() + components);
            String value =
                    switch (input.kind()) {
                        case INT -> words;
                        case UINT -> (components == 1 ? "uint" : "uvec" + components) + "(" + words + ")";
                        case FLOAT -> "intBitsToFloat(" + words + ")";
                    };
            result = declaration.replaceFirst(
                    Matcher.quoteReplacement("#define " + input.name() + " (" + type + "(" + value + "))"));
        }
        // The extension and the block go straight after the version line, before anything reads them.
        String header = "#extension GL_ANGLE_multi_draw : require\n" + "layout(std140) uniform " + BLOCK
                + "\n{\n    highp ivec4 incari_draw_rows[" + ROWS + "];\n};\n";
        Matcher again = VERSION.matcher(result);
        if (!again.find()) {
            throw new IllegalStateException("Lost the version line");
        }
        return result.substring(0, again.end()) + header + result.substring(again.end());
    }

    /** Components of a scalar or vector type SPIRV-Cross declares an input as. */
    static int components(String type) {
        return switch (type) {
            case "int", "uint", "float" -> 1;
            case "ivec2", "uvec2", "vec2" -> 2;
            case "ivec3", "uvec3", "vec3" -> 3;
            case "ivec4", "uvec4", "vec4" -> 4;
            default -> throw new IllegalArgumentException("Can't read a " + type + " from a row");
        };
    }
}
