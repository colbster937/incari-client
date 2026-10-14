package incari.tools.shaders;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands the game's {@code #include <namespace:file.glsl>} lines in place, as shaderc's include
 * callback does for the desktop game. Every include is inserted where it stands, even inside a
 * disabled {@code #if}: the preprocessor drops it afterwards, and the files carry their own
 * include guards, so the result compiles the same.
 */
final class Includes {

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]\\s*$");

    /** Deeper than any real chain; only a cycle gets here. */
    private static final int MAX_DEPTH = 32;

    private Includes() {}

    static String expand(String source, Map<String, String> includes) throws ShaderException {
        StringBuilder out = new StringBuilder(source.length() * 2);
        expand(source, includes, out, 0);
        return out.toString();
    }

    private static void expand(String source, Map<String, String> includes, StringBuilder out, int depth)
            throws ShaderException {
        if (depth > MAX_DEPTH) {
            throw new ShaderException("Includes nest deeper than " + MAX_DEPTH + "; is there a cycle?");
        }
        for (String line : source.split("\n", -1)) {
            Matcher include = INCLUDE.matcher(line);
            if (!include.matches()) {
                out.append(line).append('\n');
                continue;
            }
            String id = include.group(1);
            String name = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
            String included = includes.get(name);
            if (included == null) {
                throw new ShaderException("Include not found: " + id);
            }
            expand(included, includes, out, depth + 1);
        }
    }
}
