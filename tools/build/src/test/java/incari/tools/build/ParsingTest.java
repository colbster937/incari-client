package incari.tools.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParsingTest {

    @Test
    void classManifestParsesDeletesReplacesAndComments() {
        ClassManifest manifest = ClassManifest.parse(
                List.of(
                        "# comment",
                        "",
                        "delete  a.b.Telemetry   # trailing comment",
                        "replace a.b.Buffer  render-webgl2/src/Buffer.java"),
                "classes.txt");

        assertThat(manifest.entries())
                .containsExactly(
                        new ClassManifest.Delete("a.b.Telemetry"),
                        new ClassManifest.Replace("a.b.Buffer", "render-webgl2/src/Buffer.java"));
        assertThat(manifest.treePaths())
                .containsExactlyInAnyOrder("src/main/java/a/b/Telemetry.java", "src/main/java/a/b/Buffer.java");
    }

    @Test
    void classManifestRejectsBadLines() {
        assertThatThrownBy(() -> ClassManifest.parse(List.of("remove a.B"), "m"))
                .hasMessageContaining("m:1")
                .hasMessageContaining("unknown action");
        assertThatThrownBy(() -> ClassManifest.parse(List.of("delete a.B$Inner"), "m"))
                .hasMessageContaining("top-level");
        assertThatThrownBy(() -> ClassManifest.parse(List.of("replace a.B work/B.java"), "m"))
                .hasMessageContaining("outside work/");
        assertThatThrownBy(() -> ClassManifest.parse(List.of("replace a.B ../B.java"), "m"))
                .hasMessageContaining("outside work/");
        assertThatThrownBy(() -> ClassManifest.parse(List.of("delete a.B", "delete a.B"), "m"))
                .hasMessageContaining("twice");
    }

    @Test
    void patchFileNamesComeFromTheSubject() {
        assertThat(PatchSet.fileFor(3, "render: Route section compile to the mesher"))
                .isEqualTo("render/0003-route-section-compile-to-the-mesher.patch");
        assertThat(PatchSet.slug("Fix `MemoryUtil` (again!)")).isEqualTo("fix-memoryutil-again");
        assertThat(PatchSet.slug("a".repeat(30) + " " + "b".repeat(40))).isEqualTo("a".repeat(30));
        assertThatThrownBy(() -> PatchSet.fileFor(1, "Render: capitalised subsystem"))
                .isInstanceOf(CliException.class);
    }

    @Test
    void patchListRejectsDuplicateNumbersAndStrayFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("render"));
        Files.createDirectories(dir.resolve("net"));
        Files.writeString(dir.resolve("render/0002-b.patch"), "");
        Files.writeString(dir.resolve("net/0001-a.patch"), "");
        assertThat(PatchSet.list(dir)).extracting(PatchSet.PatchFile::subsystem).containsExactly("net", "render");

        Files.writeString(dir.resolve("net/0002-c.patch"), "");
        assertThatThrownBy(() -> PatchSet.list(dir)).hasMessageContaining("share number 2");

        Files.delete(dir.resolve("net/0002-c.patch"));
        Files.writeString(dir.resolve("loose.patch"), "");
        assertThatThrownBy(() -> PatchSet.list(dir)).hasMessageContaining("Unexpected patch file");
    }

    @Test
    void versionJsonKeepsOnlyArtifactsAndFlagsNatives() {
        String json = """
                {
                  "id": "26.3",
                  "javaVersion": {"component": "java-runtime-epsilon", "majorVersion": 25},
                  "downloads": {"client": {"url": "https://example.invalid/client.jar", "sha1": "abc", "size": 10}},
                  "libraries": [
                    {"name": "org.example:lib:1.0",
                     "downloads": {"artifact": {"path": "org/example/lib/1.0/lib-1.0.jar", "url": "https://example.invalid/lib.jar", "sha1": "def", "size": 5}}},
                    {"name": "org.example:lib:1.0:natives-linux",
                     "downloads": {"artifact": {"path": "org/example/lib/1.0/lib-1.0-natives-linux.jar", "url": "https://example.invalid/n.jar", "sha1": "123", "size": 5}},
                     "rules": [{"action": "allow", "os": {"name": "linux"}}]},
                    {"name": "org.example:no-artifact:1.0"}
                  ]
                }
                """;
        MojangMetadata.Version version = MojangMetadata.parseVersion(json);

        assertThat(version.javaMajorVersion()).isEqualTo(25);
        assertThat(version.client().sha1()).isEqualTo("abc");
        assertThat(version.libraries())
                .extracting(MojangMetadata.Library::name, MojangMetadata.Library::platformSpecific)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("org.example:lib:1.0", false),
                        org.assertj.core.groups.Tuple.tuple("org.example:lib:1.0:natives-linux", true));
    }

    @Test
    void manifestLookupFindsTheVersionJson() {
        String manifest = """
                {"latest": {"release": "26.3"},
                 "versions": [{"id": "26.3", "url": "https://example.invalid/26.3.json", "sha1": "bc09"}]}
                """;
        assertThat(MojangMetadata.findVersion(manifest, "26.3").sha1()).isEqualTo("bc09");
        assertThatThrownBy(() -> MojangMetadata.findVersion(manifest, "1.0")).isInstanceOf(CliException.class);
    }

    @Test
    void workspaceIsFoundFromASubdirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(BuildConfig.FILE_NAME), "");
        Path nested = Files.createDirectories(dir.resolve("a/b"));
        assertThat(Workspace.discover(nested).repoRoot())
                .isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void nativePlatformNamesMatchMojangsClassifiers() {
        assertThat(NativePlatform.of("Linux", "amd64").classifier()).isEqualTo("natives-linux");
        assertThat(NativePlatform.of("Windows 11", "aarch64").classifier()).isEqualTo("natives-windows-arm64");
        assertThat(NativePlatform.of("Mac OS X", "aarch64").classifier()).isEqualTo("natives-macos-arm64");
        assertThatThrownBy(() -> NativePlatform.of("FreeBSD", "amd64")).isInstanceOf(CliException.class);
    }

    @Test
    void hostNativesPickOnlyThisPlatformsJars() {
        String json = """
                {
                  "id": "26.3",
                  "javaVersion": {"majorVersion": 25},
                  "downloads": {"client": {"url": "https://example.invalid/c.jar", "sha1": "abc", "size": 1}},
                  "libraries": [
                    {"name": "org.lwjgl:lwjgl:3.4.3",
                     "downloads": {"artifact": {"path": "l.jar", "url": "https://example.invalid/l.jar", "sha1": "1", "size": 1}}},
                    {"name": "org.lwjgl:lwjgl:3.4.3:natives-linux",
                     "downloads": {"artifact": {"path": "ll.jar", "url": "https://example.invalid/ll.jar", "sha1": "2", "size": 1}}},
                    {"name": "org.lwjgl:lwjgl:3.4.3:natives-windows",
                     "downloads": {"artifact": {"path": "lw.jar", "url": "https://example.invalid/lw.jar", "sha1": "3", "size": 1}}}
                  ]
                }
                """;
        MojangMetadata.Version version = MojangMetadata.parseVersion(json);

        assertThat(GameFiles.hostNatives(version, NativePlatform.of("Linux", "amd64")))
                .extracting(MojangMetadata.Library::path)
                .containsExactly("ll.jar");
        assertThat(version.libraries().getFirst().classifier()).isEmpty();
    }
}
