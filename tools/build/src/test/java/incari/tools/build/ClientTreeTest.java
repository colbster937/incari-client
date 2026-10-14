package incari.tools.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The apply/rebuild cycle on a tiny synthetic "vendor" tree, so the tests need neither
 * network nor Mojang's jar.
 */
class ClientTreeTest {

    @TempDir
    Path root;

    private Workspace ws;
    private Path decompiled;
    private ClientTree tree;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve(BuildConfig.FILE_NAME), "minecraft.version=test\n");
        ws = new Workspace(root);
        decompiled = ws.decompiledDir("test");
        writeVendor("com/example/Greeter.java", """
                package com.example;

                public class Greeter {
                    public String greet() {
                        return "hello";
                    }
                }
                """);
        writeVendor("com/example/Telemetry.java", "package com.example;\n\npublic class Telemetry {}\n");
        writeVendor("com/example/Buffer.java", "package com.example;\n\npublic class Buffer { int pointer; }\n");
        Files.writeString(decompiled.resolve(".incari-decompile-stamp"), "stamp-1");
        tree = new ClientTree(ws);
    }

    @Test
    void rebuildThenApplyRoundTripsByteForByte() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", "Explains why.");

        ClientTree.RebuildResult first = tree.rebuildPatches(false);
        Path patch = root.resolve("patches/core/0001-change-the-greeting.patch");
        assertThat(first.written()).isEqualTo(1);
        assertThat(patch).exists();
        String text = Files.readString(patch);
        assertThat(text)
                .contains("Subject: [PATCH] core: Change the greeting")
                .contains("From: Tester <tester@example.com>")
                .doesNotContain("public class Greeter"); // no function-context hunk header

        // Fresh apply from the patch file, then regenerate: nothing may change.
        FileTrees.deleteRecursively(ws.clientTree());
        tree.apply(decompiled, "test", false);
        assertThat(Files.readString(treeFile("com/example/Greeter.java"))).contains("\"hi\"");
        ClientTree.RebuildResult second = tree.rebuildPatches(true);
        assertThat(second.changed()).isFalse();
        assertThat(second.unchanged()).isEqualTo(1);
    }

    @Test
    void checkModeReportsUnsavedCommitsWithoutWriting() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", null);

        assertThat(tree.rebuildPatches(true).changed()).isTrue();
        assertThat(root.resolve("patches")).doesNotExist();
    }

    @Test
    void rebuildRemovesPatchesWhoseCommitsWereDropped() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", null);
        tree.rebuildPatches(false);

        git().run("reset", "--quiet", "--hard", ClientTree.BASE_TAG);
        ClientTree.RebuildResult result = tree.rebuildPatches(false);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(root.resolve("patches/core")).doesNotExist();
    }

    @Test
    void patchesAreNumberedInOneGlobalOrderAcrossSubsystems() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", null);
        edit("com/example/Buffer.java", "int pointer;", "int offset;");
        commit("memory: Replace the raw pointer with an offset", null);
        tree.rebuildPatches(false);

        assertThat(PatchSet.list(ws.patchesDir()))
                .extracting(p -> root.relativize(p.path()).toString())
                .containsExactly(
                        "patches/core/0001-change-the-greeting.patch",
                        "patches/memory/0002-replace-the-raw-pointer-with-an-offset.patch");

        FileTrees.deleteRecursively(ws.clientTree());
        tree.apply(decompiled, "test", false);
        assertThat(tree.rebuildPatches(true).changed()).isFalse();
    }

    @Test
    void rebuildRejectsSubjectsWithoutASubsystem() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("Change the greeting", null);

        assertThatThrownBy(() -> tree.rebuildPatches(false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("<subsystem>: <summary>");
    }

    @Test
    void rebuildRejectsFileDeletionsInPatches() throws Exception {
        tree.apply(decompiled, "test", false);
        git().run("rm", "--quiet", Workspace.CLIENT_SOURCE_DIR + "/com/example/Telemetry.java");
        commit("telemetry: Remove telemetry", null);

        assertThatThrownBy(() -> tree.rebuildPatches(false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("classes.txt");
    }

    @Test
    void rebuildRejectsUncommittedEdits() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");

        assertThatThrownBy(() -> tree.rebuildPatches(false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("uncommitted changes");
    }

    @Test
    void applyRefusesToDiscardUnsavedWorkUnlessForced() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", null);

        assertThatThrownBy(() -> tree.apply(decompiled, "test", false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("commits not saved");

        tree.apply(decompiled, "test", true);
        assertThat(Files.readString(treeFile("com/example/Greeter.java"))).contains("\"hello\"");
    }

    @Test
    void classManifestDeletesAndReplacesWholeFiles() throws Exception {
        Path replacement = root.resolve("memory/src/Buffer.java");
        Files.createDirectories(replacement.getParent());
        Files.writeString(replacement, "package com.example;\n\npublic class Buffer { /* Incari */ }\n");
        Files.createDirectories(ws.patchesDir());
        Files.writeString(ws.classManifest(), """
                # test manifest
                delete  com.example.Telemetry
                replace com.example.Buffer  memory/src/Buffer.java
                """);

        tree.apply(decompiled, "test", false);

        assertThat(treeFile("com/example/Telemetry.java")).doesNotExist();
        assertThat(Files.readString(treeFile("com/example/Buffer.java"))).contains("/* Incari */");

        edit("com/example/Buffer.java", "/* Incari */", "/* edited in work/ */");
        commit("memory: Edit the replaced class", null);
        assertThatThrownBy(() -> tree.rebuildPatches(false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("replaces");
    }

    @Test
    void manifestEntryForAMissingClassFailsClearly() throws Exception {
        Files.createDirectories(ws.patchesDir());
        Files.writeString(ws.classManifest(), "delete com.example.Gone\n");

        assertThatThrownBy(() -> tree.apply(decompiled, "test", false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("com.example.Gone");
    }

    @Test
    void conflictingPatchStopsMidApplyWithInstructions() throws Exception {
        tree.apply(decompiled, "test", false);
        edit("com/example/Greeter.java", "\"hello\"", "\"hi\"");
        commit("core: Change the greeting", null);
        tree.rebuildPatches(false);

        // A new "Minecraft version" whose line the patch touches changed underneath it.
        writeVendor(
                "com/example/Greeter.java",
                Files.readString(decompiled.resolve("com/example/Greeter.java"))
                        .replace("\"hello\"", "\"hello there\""));
        Files.writeString(decompiled.resolve(".incari-decompile-stamp"), "stamp-2");

        assertThatThrownBy(() -> tree.apply(decompiled, "test", false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("am --continue");
    }

    // --- helpers ---

    private void writeVendor(String relative, String content) throws IOException {
        Path file = decompiled.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path treeFile(String relative) {
        return ws.clientTree().resolve(Workspace.CLIENT_SOURCE_DIR).resolve(relative);
    }

    private void edit(String relative, String from, String to) throws IOException {
        Path file = treeFile(relative);
        String content = Files.readString(file);
        assertThat(content).contains(from);
        Files.writeString(file, content.replace(from, to));
    }

    private void commit(String subject, String body) throws Exception {
        git().run("add", "--all");
        List<String> args = new java.util.ArrayList<>(List.of(
                "commit",
                "--quiet",
                "--author=Tester <tester@example.com>",
                "--date=2026-09-25T12:00:00+02:00",
                "-m",
                subject));
        if (body != null) {
            args.addAll(List.of("-m", body));
        }
        git().run(args.toArray(String[]::new));
    }

    private Git git() {
        return new Git(ws.clientTree());
    }
}
