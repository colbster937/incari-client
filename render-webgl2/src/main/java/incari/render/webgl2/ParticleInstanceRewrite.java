package incari.render.webgl2;

import incari.render.webgl2.shaders.TranslatedStage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the game's particle vertex shader to draw particles instanced (ADR 0032): each instance
 * is one particle as the game keeps it ({@code QuadParticleRenderState}'s twelve floats and two ints
 * a particle), and the four vertices of its quad are worked out here, from {@code gl_VertexID}
 * (the quad index buffer's 0 to 3), as the game works them out on the CPU: the corner of the unit
 * square turned by the particle's rotation (JOML's quaternion rotation, its normalization included),
 * scaled, moved to the particle; its texture coordinates the corner's of the sprite's. The shader's
 * own {@code Position}, {@code UV0} and {@code Color} inputs read those, so the rest of it is
 * unchanged; {@code UV2} (the light) is read per instance as it was per vertex.
 *
 * <p>The color arrives as the game's packed int (ARGB) as it lies in memory, so its bytes are blue,
 * green, red, alpha, and the shader turns them round. The light's two halves are its two shorts, as a
 * vertex holds them.
 *
 * <p>Pure text over SPIRV-Cross's output (one input declaration per line), tested without a browser.
 */
final class ParticleInstanceRewrite {

    /** The shader define that marks an instanced copy of a particle pipeline; no shader reads it. */
    static final String FLAG = "INCARI_PARTICLES_INSTANCED";

    /** Per instance: the particle's position. */
    static final String CENTER = "IncariCenter";

    /** Per instance: its rotation, a quaternion (x, y, z, w). */
    static final String ROTATION = "IncariRotation";

    /** Per instance: its scale. */
    static final String SCALE = "IncariScale";

    /** Per instance: its sprite's texture coordinates, u0, u1, v0, v1. */
    static final String UVS = "IncariUvs";

    /** Per instance: its color, the game's ARGB int as bytes. */
    static final String COLOR = "IncariColor";

    private static final Pattern VERSION = Pattern.compile("(?m)^#version 300 es\\n");

    private ParticleInstanceRewrite() {}

    /**
     * {@code stage} drawing instances.
     *
     * @throws IllegalArgumentException if the shader doesn't declare the particle inputs
     */
    static TranslatedStage rewrite(TranslatedStage stage) {
        Matcher version = VERSION.matcher(stage.glsl());
        if (!version.find()) {
            throw new IllegalArgumentException("Not GLSL ES 3.00");
        }
        int next = 0;
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            next = Math.max(next, attribute.location() + 1);
        }
        int rotation = next;
        int scale = next + 1;
        int uvs = next + 2;
        String glsl = stage.glsl();
        glsl = replaceDeclaration(
                glsl,
                "vec3",
                "Position",
                "layout(location = " + location(stage, "Position") + ") in vec3 " + CENTER + ";\n"
                        + "layout(location = " + rotation + ") in vec4 " + ROTATION + ";\n"
                        + "layout(location = " + scale + ") in float " + SCALE + ";\n"
                        + "layout(location = " + uvs + ") in vec4 " + UVS + ";\n"
                        + "vec3 incari_particle_position()\n{\n"
                        + "    int corner = gl_VertexID & 3;\n"
                        + "    float cx = corner < 2 ? 1.0 : -1.0;\n"
                        + "    float cy = (corner == 1 || corner == 2) ? 1.0 : -1.0;\n"
                        // JOML's Vector3f.rotate(Quaternionfc), with z = 0.
                        + "    vec4 q = " + ROTATION + ";\n"
                        + "    float xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z, ww = q.w * q.w;\n"
                        + "    float xy = q.x * q.y, xz = q.x * q.z, yz = q.y * q.z;\n"
                        + "    float xw = q.x * q.w, zw = q.z * q.w, yw = q.y * q.w;\n"
                        + "    float k = 1.0 / (xx + yy + zz + ww);\n"
                        + "    vec3 turned = vec3(\n"
                        + "        (xx - yy - zz + ww) * k * cx + (2.0 * (xy - zw) * k) * cy,\n"
                        + "        (2.0 * (xy + zw) * k) * cx + (yy - xx - zz + ww) * k * cy,\n"
                        + "        (2.0 * (xz - yw) * k) * cx + (2.0 * (yz + xw) * k) * cy);\n"
                        + "    return turned * " + SCALE + " + " + CENTER + ";\n}\n"
                        + "#define Position incari_particle_position()");
        glsl = replaceDeclaration(
                glsl,
                "vec2",
                "UV0",
                "vec2 incari_particle_uv()\n{\n"
                        + "    int corner = gl_VertexID & 3;\n"
                        + "    return vec2(corner < 2 ? " + UVS + ".y : " + UVS + ".x, (corner == 1 || corner == 2) ? "
                        + UVS + ".z : " + UVS + ".w);\n}\n"
                        + "#define UV0 incari_particle_uv()");
        glsl = replaceDeclaration(
                glsl,
                "vec4",
                "Color",
                "layout(location = " + location(stage, "Color") + ") in vec4 " + COLOR + ";\n" + "#define Color ("
                        + COLOR + ".zyxw)");
        List<TranslatedStage.Attribute> attributes = new ArrayList<>();
        for (TranslatedStage.Attribute attribute : stage.attributes()) {
            switch (attribute.name()) {
                case "Position" -> attributes.add(new TranslatedStage.Attribute(CENTER, attribute.location(), false));
                case "Color" -> attributes.add(new TranslatedStage.Attribute(COLOR, attribute.location(), false));
                case "UV0" -> {
                    // Worked out from the corner: no input any more.
                }
                default -> attributes.add(attribute);
            }
        }
        attributes.add(new TranslatedStage.Attribute(ROTATION, rotation, false));
        attributes.add(new TranslatedStage.Attribute(SCALE, scale, false));
        attributes.add(new TranslatedStage.Attribute(UVS, uvs, false));
        return new TranslatedStage(
                stage.key(),
                stage.sourceHash(),
                glsl,
                attributes,
                stage.uniformBlocks(),
                stage.samplers(),
                stage.unsupported());
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
