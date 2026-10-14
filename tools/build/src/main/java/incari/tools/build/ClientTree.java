package incari.tools.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The editable client tree in {@code work/client}: a git repository whose history is
 *
 * <ol>
 *   <li>one vendor commit (Vineflower's output, untouched), tagged {@code vendor};
 *   <li>one commit applying {@code patches/classes.txt}, tagged {@code base};
 *   <li>one commit per patch file.
 * </ol>
 *
 * <p>People edit the tree and commit there (one concern per commit, subject
 * {@code "<subsystem>: <summary>"}), then {@link #rebuildPatches} turns the commits after
 * {@code base} back into patch files. Patch files are never edited by hand.
 */
public final class ClientTree {

    public static final String VENDOR_TAG = "vendor";
    public static final String BASE_TAG = "base";

    /** HEAD at the last apply or rebuild. Anything else means unsaved work. */
    private static final String SYNCED_HEAD_FILE = "incari-synced-head";
    /** Which decompiled output the vendor commit holds, so re-applying can skip the copy. */
    private static final String VENDOR_STAMP_FILE = "incari-vendor-stamp";

    /**
     * Minimal context keeps Mojang lines out of patch files (a unified diff repeats its context
     * lines). Full blob ids let {@code git am --3way} fall back to a merge when context drifts.
     */
    private static final List<String> FORMAT_PATCH_OPTIONS = List.of(
            "--stdout",
            "--zero-commit",
            "--no-signature",
            "--no-stat",
            "--no-numbered",
            "--no-renames",
            "--full-index",
            "--no-color",
            "--no-ext-diff",
            "-U1");

    private final Workspace ws;
    private final Git git;

    public ClientTree(Workspace ws) {
        this.ws = ws;
        this.git = new Git(ws.clientTree());
    }

    // ------------------------------------------------------------------------------------
    // Apply: decompiled sources + manifest + patches -> work/client
    // ------------------------------------------------------------------------------------

    /**
     * Rebuilds the tree from the decompiled sources and the tracked patches. Refuses to throw
     * away commits or edits that {@code rebuild} hasn't saved, unless {@code force}.
     */
    public void apply(Path decompiledDir, String version, boolean force) throws IOException, InterruptedException {
        Path tree = ws.clientTree();
        if (Files.isDirectory(tree.resolve(".git")) && !force) {
            requireNoUnsavedWork();
        }

        String vendorStamp = Files.readString(decompiledDir.resolve(".incari-decompile-stamp"));
        if (vendorIsCurrent(vendorStamp)) {
            Log.step("Resetting work/client to the vendor commit");
            abortInProgressAm();
            git.run("reset", "--quiet", "--hard", VENDOR_TAG);
            git.run("clean", "--quiet", "-fdx");
        } else {
            createVendorCommit(decompiledDir, version, vendorStamp);
        }
        writeAttributes();

        applyManifest();
        applyPatches();
        recordSyncedHead();
    }

    private boolean vendorIsCurrent(String vendorStamp) throws IOException, InterruptedException {
        Path stampFile = ws.clientTree().resolve(".git").resolve(VENDOR_STAMP_FILE);
        return Files.isRegularFile(stampFile)
                && Files.readString(stampFile).equals(vendorStamp)
                && git.tryRun("rev-parse", "--verify", "--quiet", VENDOR_TAG).exitCode() == 0;
    }

    private void createVendorCommit(Path decompiledDir, String version, String vendorStamp)
            throws IOException, InterruptedException {
        Path tree = ws.clientTree();
        Log.step("Creating work/client from the decompiled " + version + " sources");
        FileTrees.deleteRecursively(tree);
        Files.createDirectories(tree);
        git.run("init", "--quiet");
        int files = FileTrees.copyTree(decompiledDir, tree.resolve(Workspace.CLIENT_SOURCE_DIR), ".java");
        Log.info(files + " source files");
        git.run("add", "--all");
        git.run("commit", "--quiet", "--no-verify", "-m", "Vendor: Minecraft " + version + " (decompiled, unmodified)");
        git.run("tag", VENDOR_TAG);
        Files.writeString(tree.resolve(".git").resolve(VENDOR_STAMP_FILE), vendorStamp);
    }

    private void writeAttributes() throws IOException {
        Path info = ws.clientTree().resolve(".git").resolve("info");
        Files.createDirectories(info);
        Files.writeString(info.resolve("attributes"), "* diff=incari\n");
    }

    private void applyManifest() throws IOException, InterruptedException {
        ClassManifest manifest = ClassManifest.load(ws.classManifest());
        Path tree = ws.clientTree();
        int deleted = 0;
        int replaced = 0;
        for (ClassManifest.Entry entry : manifest.entries()) {
            Path target = tree.resolve(entry.treePath());
            switch (entry) {
                case ClassManifest.Delete delete -> {
                    requireExists(target, delete.className(), "delete");
                    Files.delete(target);
                    deleted++;
                }
                case ClassManifest.Replace replace -> {
                    requireExists(target, replace.className(), "replace");
                    Path source = ws.repoRoot().resolve(replace.source());
                    if (!Files.isRegularFile(source)) {
                        throw new CliException(ClassManifest.FILE_NAME + ": replacement source " + replace.source()
                                + " for " + replace.className() + " doesn't exist");
                    }
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    replaced++;
                }
            }
        }
        git.run("add", "--all");
        git.run(
                "commit",
                "--quiet",
                "--no-verify",
                "--allow-empty",
                "-m",
                "Incari: class manifest (" + deleted + " deleted, " + replaced + " replaced)");
        git.run("tag", "--force", BASE_TAG);
        if (!manifest.entries().isEmpty()) {
            Log.info("Class manifest: " + deleted + " deleted, " + replaced + " replaced");
        }
    }

    private static void requireExists(Path target, String className, String action) {
        if (!Files.isRegularFile(target)) {
            throw new CliException(ClassManifest.FILE_NAME + ": can't " + action + " " + className
                    + ", it isn't in the vendor tree (renamed or removed in this Minecraft version?)");
        }
    }

    private void applyPatches() throws IOException, InterruptedException {
        List<PatchSet.PatchFile> patches = PatchSet.list(ws.patchesDir());
        if (patches.isEmpty()) {
            Log.step("No patches to apply");
            return;
        }
        Log.step("Applying " + patches.size() + " patches");
        List<String> args = new ArrayList<>(List.of("am", "--quiet", "--3way", "--keep-cr", "--no-gpg-sign"));
        for (PatchSet.PatchFile patch : patches) {
            args.add(patch.path().toString());
        }
        ProcessRunner.Result result = git.tryRun(args.toArray(String[]::new));
        if (result.exitCode() != 0) {
            String current =
                    git.tryRun("am", "--show-current-patch=diff").exitCode() == 0 ? currentAmPatchName() : "a patch";
            throw new CliException(
                    "Patch failed to apply: " + current + "\n" + result.stderr().strip() + "\n"
                            + result.stdout().strip() + "\n\n"
                            + "work/client is stopped mid-apply. To fix it: resolve the conflicts in work/client, then\n"
                            + "  git -C work/client add <files> && git -C work/client am --continue\n"
                            + "and once every patch is in, save the result with ./incari rebuild");
        }
    }

    private String currentAmPatchName() throws IOException {
        Path next = ws.clientTree().resolve(".git/rebase-apply/next");
        Path last = ws.clientTree().resolve(".git/rebase-apply/last");
        if (!Files.isRegularFile(next)) {
            return "a patch";
        }
        String n = Files.readString(next).strip();
        return "patch " + n + " of " + Files.readString(last).strip();
    }

    private void abortInProgressAm() throws IOException, InterruptedException {
        if (Files.isDirectory(ws.clientTree().resolve(".git/rebase-apply"))) {
            git.run("am", "--abort");
        }
    }

    // ------------------------------------------------------------------------------------
    // Rebuild: commits after `base` -> patches/
    // ------------------------------------------------------------------------------------

    /** What a rebuild wrote, or (with {@code check}) would write. */
    public record RebuildResult(int written, int unchanged, int removed) {
        public boolean changed() {
            return written > 0 || removed > 0;
        }
    }

    /**
     * Turns every commit after {@code base} into a patch file. With {@code check}, writes
     * nothing and only reports whether {@code patches/} is out of date (for CI).
     */
    public RebuildResult rebuildPatches(boolean check) throws IOException, InterruptedException {
        requireTree();
        if (Files.isDirectory(ws.clientTree().resolve(".git/rebase-apply"))) {
            throw new CliException("work/client is in the middle of applying patches. Finish with"
                    + " `git -C work/client am --continue` (or start over with ./incari apply --force).");
        }
        if (!git.run("status", "--porcelain").isBlank()) {
            throw new CliException("work/client has uncommitted changes. Commit them first (one concern per commit,"
                    + " subject \"<subsystem>: <summary>\"), then run ./incari rebuild.");
        }
        if (git.tryRun("merge-base", "--is-ancestor", BASE_TAG, "HEAD").exitCode() != 0) {
            throw new CliException(
                    "HEAD in work/client doesn't build on the '" + BASE_TAG + "' tag. Rebase your commits onto it.");
        }

        Set<String> manifestPaths = ClassManifest.load(ws.classManifest()).treePaths();
        List<String> commits =
                git.run("rev-list", "--reverse", BASE_TAG + "..HEAD").lines().toList();

        Map<String, byte[]> generated = new LinkedHashMap<>();
        for (int i = 0; i < commits.size(); i++) {
            String commit = commits.get(i);
            validateCommit(commit, manifestPaths);
            String subject = git.run("log", "-1", "--format=%s", commit).strip();
            String file = PatchSet.fileFor(i + 1, subject);
            List<String> args = new ArrayList<>(List.of("format-patch", "-1"));
            args.addAll(FORMAT_PATCH_OPTIONS);
            args.add(commit);
            generated.put(file, git.run(args.toArray(String[]::new)).getBytes(StandardCharsets.UTF_8));
        }

        return writePatches(generated, check);
    }

    private void validateCommit(String commit, Set<String> manifestPaths) throws IOException, InterruptedException {
        String shortId = commit.substring(0, 10);
        if (git.run("rev-list", "--parents", "-n", "1", commit).strip().split(" ").length > 2) {
            throw new CliException(
                    "Commit " + shortId + " is a merge. Patches must be a linear history; rebase instead.");
        }
        String changes = git.run("diff-tree", "--no-commit-id", "--name-status", "-r", "--no-renames", commit);
        for (String line : changes.lines().toList()) {
            String[] parts = line.split("\t", 2);
            String status = parts[0];
            String path = parts[1];
            if (status.startsWith("D")) {
                throw new CliException("Commit " + shortId + " deletes " + path + ". A patch that deletes a file"
                        + " republishes all of it; add a `delete` line to patches/classes.txt instead.");
            }
            if (manifestPaths.contains(path)) {
                throw new CliException("Commit " + shortId + " edits " + path + ", which patches/classes.txt deletes or"
                        + " replaces. Edit the replacement source in the repo instead.");
            }
        }
    }

    private RebuildResult writePatches(Map<String, byte[]> generated, boolean check)
            throws IOException, InterruptedException {
        Path patchesDir = ws.patchesDir();
        List<Path> existing = new ArrayList<>();
        if (Files.isDirectory(patchesDir)) {
            try (Stream<Path> files = Files.walk(patchesDir)) {
                files.filter(p -> p.toString().endsWith(".patch")).forEach(existing::add);
            }
        }

        int written = 0;
        int unchanged = 0;
        for (Map.Entry<String, byte[]> entry : generated.entrySet()) {
            Path target = patchesDir.resolve(entry.getKey());
            existing.remove(target);
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), entry.getValue())) {
                unchanged++;
                continue;
            }
            written++;
            if (check) {
                Log.info("out of date: patches/" + entry.getKey());
            } else {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
        }
        for (Path stale : existing) {
            if (check) {
                Log.info("stale: " + ws.repoRoot().relativize(stale));
            } else {
                Files.delete(stale);
                Path parent = stale.getParent();
                if (parent != null) {
                    deleteIfEmpty(parent);
                }
            }
        }
        if (!check) {
            recordSyncedHead();
        }
        return new RebuildResult(written, unchanged, existing.size());
    }

    private void deleteIfEmpty(Path dir) throws IOException {
        if (dir.equals(ws.patchesDir())) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) {
                Files.delete(dir);
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Guarding unsaved work
    // ------------------------------------------------------------------------------------

    private void recordSyncedHead() throws IOException, InterruptedException {
        String head = git.run("rev-parse", "HEAD").strip();
        Files.writeString(ws.clientTree().resolve(".git").resolve(SYNCED_HEAD_FILE), head);
    }

    private void requireNoUnsavedWork() throws IOException, InterruptedException {
        Path synced = ws.clientTree().resolve(".git").resolve(SYNCED_HEAD_FILE);
        if (!Files.isRegularFile(synced)) {
            // A tree from an interrupted first apply: nothing in it was ever the user's.
            return;
        }
        boolean dirty = !git.run("status", "--porcelain").isBlank();
        boolean moved = !git.run("rev-parse", "HEAD")
                .strip()
                .equals(Files.readString(synced).strip());
        if (dirty || moved) {
            throw new CliException(
                    "work/client has " + (dirty ? "uncommitted changes" : "commits not saved to patches/")
                            + ". Applying would discard them.\n"
                            + "  Save them first: commit, then ./incari rebuild\n"
                            + "  Or throw them away: ./incari apply --force");
        }
    }

    private void requireTree() {
        if (!Files.isDirectory(ws.clientTree().resolve(".git"))) {
            throw new CliException("work/client doesn't exist yet. Run: ./incari setup");
        }
    }
}
