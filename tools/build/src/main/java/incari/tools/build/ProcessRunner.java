package incari.tools.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Runs child processes (git, the decompiler JVM) and turns failures into {@link CliException}s. */
public final class ProcessRunner {

    /** Variables with which git tells a hook which repository it runs in. */
    private static final List<String> OUTER_GIT_ENVIRONMENT = List.of(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_COMMON_DIR",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_PREFIX");

    private ProcessRunner() {}

    public record Result(int exitCode, String stdout, String stderr) {}

    /**
     * Runs {@code command} in {@code dir}. With {@code inheritOutput}, the child writes straight
     * to the console (long-running steps the user watches); otherwise output is captured.
     * Throws on a non-zero exit.
     */
    public static Result run(List<String> command, Path dir, boolean inheritOutput)
            throws IOException, InterruptedException {
        Result result = start(command, dir, Map.of(), inheritOutput, null);
        if (result.exitCode() != 0) {
            throw new CliException("Command failed (exit " + result.exitCode() + "): " + String.join(" ", command)
                    + (result.stderr().isBlank() ? "" : "\n" + result.stderr().strip()));
        }
        return result;
    }

    /** Runs {@code command} and returns its result whatever the exit code. */
    public static Result start(
            List<String> command, Path dir, Map<String, String> env, boolean inheritOutput, @Nullable String stdin)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        // Run from a git hook (the pre-commit hook's `rebuild --check`), the CLI inherits the outer
        // repo's GIT_DIR and GIT_INDEX_FILE, which would point git in work/client at the wrong
        // repository. Every command here names its own directory.
        builder.environment().keySet().removeAll(OUTER_GIT_ENVIRONMENT);
        builder.environment().putAll(env);
        if (inheritOutput) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        if (stdin == null) {
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        }
        Process process = builder.start();
        if (stdin != null) {
            try (var in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (inheritOutput) {
            return new Result(process.waitFor(), "", "");
        }
        // Drain stderr on its own thread: a child that fills one pipe while we block reading
        // the other would deadlock.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err));
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        errReader.join();
        return new Result(exit, out, err.toString(StandardCharsets.UTF_8));
    }

    /** The {@code java} binary running this CLI, so child JVMs use the same JDK. */
    public static String javaExecutable() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
