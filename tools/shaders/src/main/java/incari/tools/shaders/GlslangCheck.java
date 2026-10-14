package incari.tools.shaders;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks translated GLSL ES with glslang ({@code glslangValidator}), the reference front end: each
 * vertex and fragment pair is compiled and linked as one ES 3.00 program, which also catches
 * varyings that don't line up between the stages. It checks the language only; what a particular
 * browser's compiler accepts is checked when the device links the programs in the page.
 */
final class GlslangCheck implements AutoCloseable {

    private final Path validator;
    private final Path scratch;
    private int files;

    GlslangCheck(Path validator) throws IOException {
        this.validator = validator;
        this.scratch = Files.createTempDirectory("incari-shaders");
    }

    /** Compiles and links one program; returns glslang's complaint, or null if it's valid. */
    @Nullable
    String link(String vertexGlsl, String fragmentGlsl) throws IOException, InterruptedException {
        // glslang picks the stage from the extension.
        Path vertex = scratch.resolve("s" + files + ".vert");
        Path fragment = scratch.resolve("s" + files + ".frag");
        files++;
        Files.writeString(vertex, vertexGlsl, StandardCharsets.UTF_8);
        Files.writeString(fragment, fragmentGlsl, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(validator.toString(), "-l", vertex.toString(), fragment.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "glslangValidator timed out";
        }
        Files.delete(vertex);
        Files.delete(fragment);
        if (process.exitValue() == 0) {
            return null;
        }
        return output.strip();
    }

    /**
     * A GLSL ES restriction that desktop GLSL doesn't have, found in glslang's output.
     *
     * @param what the construct ES lacks, for the report
     * @param vertex whether the vertex stage (rather than the fragment stage) uses it
     */
    record EsLimit(String what, boolean vertex) {}

    // glslang: "ERROR: <file>.frag:113: 'variable indexing fragment shader output array' : not
    // supported with this profile: es"
    private static final Pattern ES_LIMIT =
            Pattern.compile("ERROR: \\S+\\.(vert|frag):\\d+: '([^']+)' : not supported with this profile: es");

    /** The ES-only restriction that {@code problem} (glslang's output) reports, or null for any other error. */
    static @Nullable EsLimit esLimit(String problem) {
        Matcher matcher = ES_LIMIT.matcher(problem);
        return matcher.find() ? new EsLimit(matcher.group(2), matcher.group(1).equals("vert")) : null;
    }

    /** glslangValidator from the path, or null if it isn't installed. */
    static @Nullable Path find() {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator, -1)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, "glslangValidator");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(scratch);
    }
}
