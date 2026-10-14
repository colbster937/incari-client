package incari.render.webgl2;

import incari.render.webgl2.shaders.TranslatedStage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the game's entity vertex shader to draw a model instanced (ADR 0024): the vertices
 * are the model's cubes once, in each part's own space, and every instance places them with its
 * own part matrices. A vertex says which part it belongs to ({@link #PART}); an instance says where
 * its rows start in {@link #BLOCK} ({@link #FIRST_ROW}), three rows (a 3×4 matrix) per part. The
 * shader's own {@code Position} and {@code Normal} inputs become the part's transform of the local
 * position and normal, so the rest of it reads exactly what it read before; the color, overlay and
 * light it reads as they are, per instance instead of per vertex.
 *
 * <p>The normal goes through the inverse transpose of the part's matrix and is normalized, which is
 * what the game's pose stack does to a normal (it keeps that matrix alongside the pose).
 *
 * <p>Pure text over SPIRV-Cross's output (one input declaration per line), tested without a browser.
 */
final class EntityInstanceRewrite {

    /** The shader define that marks an instanced copy of an entity pipeline; no shader reads it. */
    static final String FLAG = "INCARI_INSTANCED";

    /** The uniform block of part matrices: three rows per part, all of an instance's in a run. */
    static final String BLOCK = "IncariParts";

    /** Rows in the block: 1024 rows of 16 bytes is WebGL2's smallest allowed maximum block size. */
    static final int ROWS = 1024;

    /** The per-vertex input naming the vertex's part. */
    static final String PART = "IncariPart";

    /** The per-instance input: where the instance's rows start. */
    static final String FIRST_ROW = "IncariFirstRow";

    /** What {@code Position} reads now: the vertex in its part's space. */
    static final String LOCAL_POSITION = "IncariLocalPosition";

    /** What {@code Normal} reads now: the face's normal in its part's space. */
    static final String LOCAL_NORMAL = "IncariLocalNormal";

    private static final Pattern VERSION = Pattern.compile("(?m)^#version 300 es\\n");

    private EntityInstanceRewrite() {}

    /**
     * {@code stage} drawing instances.
     *
     * @throws IllegalArgumentException if the shader doesn't declare a {@code vec3 Position} input
     */
    static TranslatedStage rewrite(TranslatedStage stage) {
        Matcher version = VERSION.matcher(stage.glsl());
        if (!version.find()) {
            throw new IllegalArgumentException("Not GLSL ES 3.00");
        }
        int nextLocation = 0;
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            nextLocation = Math.max(nextLocation, attribute.location() + 1);
        }
        int partLocation = nextLocation;
        int firstRowLocation = nextLocation + 1;
        // Declared first, so both replacements below can read them wherever their inputs were.
        String prelude = "layout(location = " + partLocation + ") in uint " + PART + ";\n"
                + "layout(location = " + firstRowLocation + ") in uint " + FIRST_ROW + ";\n"
                + "layout(std140) uniform " + BLOCK + "\n{\n    vec4 incari_part_rows[" + ROWS + "];\n} incari_ubo_"
                + BLOCK + ";\n"
                + "uint incari_part_row()\n{\n    return " + FIRST_ROW + " + " + PART + " * 3u;\n}\n";
        String glsl = version.replaceFirst(Matcher.quoteReplacement(version.group() + prelude));

        int positionLocation = location(stage, "Position");
        String rows = "incari_ubo_" + BLOCK + ".incari_part_rows";
        glsl = replaceDeclaration(
                glsl,
                "vec3",
                "Position",
                "layout(location = " + positionLocation + ") in vec3 " + LOCAL_POSITION + ";\n"
                        + "vec3 incari_part_position()\n{\n    uint row = incari_part_row();\n"
                        + "    vec4 local = vec4(" + LOCAL_POSITION + ", 1.0);\n"
                        + "    return vec3(dot(" + rows + "[row], local), dot(" + rows + "[row + 1u], local), dot("
                        + rows + "[row + 2u], local));\n}\n"
                        + "#define Position incari_part_position()");
        boolean hasNormal = has(stage, "Normal");
        if (hasNormal) {
            // mat3 takes columns: the rows as columns are the transpose, whose inverse is the
            // inverse transpose of the part's matrix.
            glsl = replaceDeclaration(
                    glsl,
                    "vec3",
                    "Normal",
                    "layout(location = " + location(stage, "Normal") + ") in vec3 " + LOCAL_NORMAL + ";\n"
                            + "vec3 incari_part_normal()\n{\n    uint row = incari_part_row();\n"
                            + "    mat3 transposed = mat3(" + rows + "[row].xyz, " + rows + "[row + 1u].xyz, " + rows
                            + "[row + 2u].xyz);\n"
                            + "    return normalize(inverse(transposed) * " + LOCAL_NORMAL + ");\n}\n"
                            + "#define Normal incari_part_normal()");
        }
        List<TranslatedStage.Attribute> attributes = new ArrayList<>();
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            switch (attribute.name()) {
                case "Position" ->
                    attributes.add(new TranslatedStage.Attribute(LOCAL_POSITION, attribute.location(), false));
                case "Normal" ->
                    attributes.add(new TranslatedStage.Attribute(LOCAL_NORMAL, attribute.location(), false));
                default -> attributes.add(attribute);
            }
        }
        attributes.add(new TranslatedStage.Attribute(PART, partLocation, true));
        attributes.add(new TranslatedStage.Attribute(FIRST_ROW, firstRowLocation, true));
        List<String> blocks = new ArrayList<>(stage.uniformBlocks());
        blocks.add(BLOCK);
        return new TranslatedStage(
                stage.key(), stage.sourceHash(), glsl, attributes, blocks, stage.samplers(), stage.unsupported());
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
