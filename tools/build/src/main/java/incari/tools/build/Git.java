package incari.tools.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs git in one repository, isolated from the user's and the system's git config.
 *
 * <p>Isolation is what makes patch files byte-identical across machines: settings like
 * {@code diff.noprefix}, {@code diff.algorithm} or {@code format.signature} in someone's
 * {@code ~/.gitconfig} would otherwise change every regenerated patch.
 */
public final class Git {

    /** Identity for commits the tool makes itself (vendor and manifest commits, applied patches). */
    static final String TOOL_NAME = "Incari Build";

    static final String TOOL_EMAIL = "build@incari.invalid";
    /** Fixed so the tool's own commits get the same hash on every machine. */
    static final String TOOL_DATE = "2000-01-01T00:00:00Z";

    private final Path dir;

    public Git(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Runs a git command, returning stdout. Throws with git's stderr on failure. */
    public String run(String... args) throws IOException, InterruptedException {
        ProcessRunner.Result result = tryRun(args);
        if (result.exitCode() != 0) {
            throw new CliException("git " + String.join(" ", args) + " failed in " + dir
                    + (result.stderr().isBlank() ? "" : ":\n" + result.stderr().strip()));
        }
        return result.stdout();
    }

    public ProcessRunner.Result tryRun(String... args) throws IOException, InterruptedException {
        return ProcessRunner.start(command(args), dir, environment(), false, null);
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "-c",
                "core.autocrlf=false",
                "-c",
                "core.safecrlf=false",
                "-c",
                "core.quotePath=false",
                "-c",
                "commit.gpgSign=false",
                "-c",
                "tag.gpgSign=false",
                // No hooks: a user's global pre-commit hook has no business running on vendor code.
                "-c",
                "core.hooksPath=.git/incari-no-hooks",
                "-c",
                "gc.auto=0",
                "-c",
                "init.defaultBranch=main",
                "-c",
                "advice.detachedHead=false",
                // Paired with the "* diff=incari" attribute ClientTree writes: a function-name
                // regex that never matches, so hunk headers don't quote a vendor line as context.
                "-c",
                "diff.incari.xfuncname=x^"));
        command.addAll(List.of(args));
        return command;
    }

    private static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_COMMITTER_NAME", TOOL_NAME);
        env.put("GIT_COMMITTER_EMAIL", TOOL_EMAIL);
        env.put("GIT_COMMITTER_DATE", TOOL_DATE);
        env.put("GIT_AUTHOR_NAME", TOOL_NAME);
        env.put("GIT_AUTHOR_EMAIL", TOOL_EMAIL);
        env.put("GIT_AUTHOR_DATE", TOOL_DATE);
        return env;
    }
}
