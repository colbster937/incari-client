package incari.tools.build;

import java.io.IOException;

/** Points the checkout's git hooks at {@code tools/git-hooks}, so the pre-commit checks run. */
final class GitHooks {

    static final String HOOKS_DIR = "tools/git-hooks";

    private GitHooks() {}

    /** Installs the hooks unless this clone already chose its own hooks directory. */
    static void install(Workspace ws) throws IOException, InterruptedException {
        Git repo = new Git(ws.repoRoot());
        if (repo.tryRun("rev-parse", "--git-dir").exitCode() != 0) {
            return; // A source archive, not a clone: nothing to install into.
        }
        // --local reads only this clone's config, not the overrides the Git class passes with -c.
        ProcessRunner.Result current = repo.tryRun("config", "--local", "--get", "core.hooksPath");
        String configured = current.stdout().strip();
        if (current.exitCode() == 0 && !configured.equals(HOOKS_DIR)) {
            Log.info("Keeping this clone's own core.hooksPath (" + configured + "); Incari's are in " + HOOKS_DIR);
            return;
        }
        if (!configured.equals(HOOKS_DIR)) {
            repo.run("config", "--local", "core.hooksPath", HOOKS_DIR);
            Log.info("Installed the pre-commit checks (" + HOOKS_DIR + ")");
        }
    }
}
