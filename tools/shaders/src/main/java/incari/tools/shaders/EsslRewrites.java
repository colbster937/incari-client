package incari.tools.shaders;

import incari.render.webgl2.shaders.TexelBuffers;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites of SPIRV-Cross's GLSL ES output for what GLSL ES 3.00 lacks and desktop GLSL has.
 * Pure text transforms on the translated source, whose shape SPIRV-Cross fixes (one declaration per
 * line, {@code void main()} on a line of its own), so they're tested without the natives.
 */
final class EsslRewrites {

    private static final String MAIN = "void main()";
    private static final String RENAMED_MAIN = "void incari_main()";

    // SPIRV-Cross: "layout(location = 0) out highp vec4 coeff[2];"
    private static final Pattern OUTPUT_ARRAY =
            Pattern.compile("layout\\(location = (\\d+)\\) out ((?:highp |mediump |lowp )?\\w+) (\\w+)\\[(\\d+)\\];");

    private EsslRewrites() {}

    /**
     * Texel buffers as 2D textures ({@link TexelBuffers}): each {@code [iu]samplerBuffer} named
     * becomes a {@code [iu]sampler2D}, and each {@code texelFetch(name, i)} reads the texel at
     * {@code (i % WIDTH, i / WIDTH)}, level 0. Also drops the texture-buffer extension SPIRV-Cross
     * asks for.
     */
    static String texelBuffers(String glsl, List<String> names) {
        if (names.isEmpty()) {
            return glsl;
        }
        String result = glsl.replaceAll("(?m)^#extension GL_(OES|EXT)_texture_buffer : (require|enable)\\n", "");
        for (String name : names) {
            Pattern declaration = Pattern.compile(
                    "uniform ((?:highp |mediump |lowp )?)([iu]?)samplerBuffer " + Pattern.quote(name) + ";");
            Matcher matcher = declaration.matcher(result);
            if (!matcher.find()) {
                throw new IllegalStateException("No samplerBuffer declaration of " + name + " in:\n" + glsl);
            }
            result = matcher.replaceFirst(Matcher.quoteReplacement("uniform highp " + matcher.group(2) + "sampler2D "
                    + name + ";\nivec2 incari_texel_" + name + "(int i)\n{\n    return ivec2(i & "
                    + (TexelBuffers.WIDTH - 1) + ", i >> " + TexelBuffers.WIDTH_SHIFT + ");\n}"));
            result = rewriteFetches(result, name);
        }
        return result;
    }

    private static String rewriteFetches(String glsl, String name) {
        String call = "texelFetch(" + name + ", ";
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int start = glsl.indexOf(call, from);
            if (start < 0) {
                break;
            }
            int argument = start + call.length();
            int end = closingParenthesis(glsl, argument);
            out.append(glsl, from, start)
                    .append("texelFetch(")
                    .append(name)
                    .append(", incari_texel_")
                    .append(name)
                    .append('(')
                    .append(glsl, argument, end)
                    .append("), 0)");
            from = end + 1;
        }
        return out.append(glsl, from, glsl.length()).toString();
    }

    /** The index of the parenthesis that closes a call whose arguments start at {@code from}. */
    private static int closingParenthesis(String glsl, int from) {
        int depth = 0;
        for (int i = from; i < glsl.length(); i++) {
            char c = glsl.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        throw new IllegalStateException("Unbalanced texelFetch call in translated shader");
    }

    /**
     * Fragment output arrays as separate outputs. GLSL ES 3.00 only lets a fragment shader index an
     * output array with a constant, and the game's order-independent transparency writes
     * {@code coeff[attachmentIndex]} in a loop. The array becomes a private global the shader writes
     * as before, and a new {@code main} copies each element to an output of its own at the element's
     * location after the game's {@code main} returns (a {@code discard} still discards).
     */
    static String fragmentOutputArrays(String glsl) {
        Matcher matcher = OUTPUT_ARRAY.matcher(glsl);
        List<String> copies = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int location = Integer.parseInt(matcher.group(1));
            String type = matcher.group(2);
            String name = matcher.group(3);
            int length = Integer.parseInt(matcher.group(4));
            StringBuilder replacement = new StringBuilder();
            for (int i = 0; i < length; i++) {
                String output = "incari_out_" + name + "_" + i;
                replacement
                        .append("layout(location = ")
                        .append(location + i)
                        .append(") out ")
                        .append(type)
                        .append(' ')
                        .append(output)
                        .append(";\n");
                copies.add("    " + output + " = " + name + "[" + i + "];\n");
            }
            replacement
                    .append(type)
                    .append(' ')
                    .append(name)
                    .append('[')
                    .append(length)
                    .append("];");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        if (copies.isEmpty()) {
            return glsl;
        }
        matcher.appendTail(out);
        String result = out.toString();
        if (!result.contains(MAIN + "\n")) {
            throw new IllegalStateException("No main() in translated shader:\n" + glsl);
        }
        result = result.replace(MAIN + "\n", RENAMED_MAIN + "\n");
        return result + "\n" + MAIN + "\n{\n    incari_main();\n" + String.join("", copies) + "}\n\n";
    }
}
