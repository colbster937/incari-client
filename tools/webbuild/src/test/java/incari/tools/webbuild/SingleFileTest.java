package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleFileTest {

    private static final Pattern FILE = Pattern.compile(
            "<script type=\"application/x-incari-file\" data-path=\"([^\"]+)\" data-type=\"([^\"]+)\"( data-encoding=\"gzip\")?>"
                    + "([A-Za-z0-9+/=]*)</script>");

    @TempDir
    Path dir;

    private Path folder;
    private Path script;

    @BeforeEach
    void fakeFolder() throws Exception {
        folder = dir.resolve("web");
        write(WebFolder.MARKER, "Written by ./incari build.");
        write("index.html", """
                <!doctype html>
                <html><head><title>Incari</title></head>
                <body>
                  <script type="module" src="boot.js"></script>
                </body>
                </html>
                """);
        write("boot.js", "import \"./loader.js\";");
        write("loader.js", "export {};");
        write("sw.js", "service worker");
        write("cache-policy.js", "its policy");
        write(".htaccess", "apache");
        write("README.txt", "DO NOT SHARE");
        write("build.json", "{\"format\": 2}");
        // Repetitive, so it packs: as a module does.
        write("incari-fast.wasm", "module ".repeat(1000));
        write("assets/objects/cc/cc33", "lang");
        Files.write(
                folder.resolve("client.jar"),
                jar(Map.of(
                        "net/minecraft/client/Main.class", "class",
                        "META-INF/MANIFEST.MF", "manifest",
                        "assets/minecraft/lang/en_us.json", "{}",
                        "data/minecraft/tags/x.json", "[]")));
        script = dir.resolve("offline.js");
        Files.writeString(script, "export function start(page) {}\n");
    }

    @Test
    void putsEveryFileThePageFetchesInOnePage() throws Exception {
        Path out = dir.resolve("incari.html");
        SingleFile.Summary summary = SingleFile.write(new SingleFile.Options(folder, out, script));

        String page = Files.readString(out);
        assertThat(page).startsWith("<!doctype html>\n" + SingleFile.SIGNATURE).contains("DO NOT SHARE");
        assertThat(page).doesNotContain("src=\"boot.js\"");
        assertThat(page)
                .contains("<script type=\"module\">\nexport function start(page) {}\n\nstart(window);\n</script>");
        assertThat(page).endsWith("</body>\n</html>\n");

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> gzipped = new ArrayList<>();
        Matcher matcher = FILE.matcher(page);
        while (matcher.find()) {
            byte[] bytes = Base64.getDecoder().decode(matcher.group(4));
            if (matcher.group(3) != null) {
                gzipped.add(matcher.group(1));
                bytes = new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes();
            }
            files.put(matcher.group(1), bytes);
        }
        assertThat(summary.files()).isEqualTo(files.size());
        // The scripts first: the page decodes them before the rest.
        assertThat(List.copyOf(files.keySet()).subList(0, 2)).containsExactlyInAnyOrder("boot.js", "loader.js");
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "boot.js",
                        "loader.js",
                        "build.json",
                        "incari-fast.wasm",
                        "assets/objects/cc/cc33",
                        "client.jar");
        assertThat(gzipped).contains("incari-fast.wasm").doesNotContain("assets/objects/cc/cc33");
        assertThat(new String(files.get("incari-fast.wasm"), StandardCharsets.UTF_8))
                .isEqualTo("module ".repeat(1000));
        assertThat(page).contains("data-path=\"incari-fast.wasm\" data-type=\"application/wasm\"");
        // The folder already cut the jar down (WebFolder.resourcesOnly): it goes in as it is.
        assertThat(files.get("client.jar")).isEqualTo(Files.readAllBytes(folder.resolve("client.jar")));
    }

    @Test
    void replacesItsOwnFileButNeverSomeoneElses() throws Exception {
        Path out = dir.resolve("incari.html");
        SingleFile.write(new SingleFile.Options(folder, out, script));
        SingleFile.write(new SingleFile.Options(folder, out, script));

        Path other = dir.resolve("mine.html");
        Files.writeString(other, "<!doctype html><p>mine</p>");
        assertThatThrownBy(() -> SingleFile.write(new SingleFile.Options(folder, other, script)))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("didn't write it");
        assertThat(other).hasContent("<!doctype html><p>mine</p>");
    }

    @Test
    void refusesAFolderItDidntWriteAndAScriptThatCantGoInline() throws Exception {
        Files.delete(folder.resolve(WebFolder.MARKER));
        assertThatThrownBy(() -> SingleFile.write(new SingleFile.Options(folder, dir.resolve("a.html"), script)))
                .isInstanceOf(CliException.class);

        write(WebFolder.MARKER, "Written by ./incari build.");
        Files.writeString(script, "const end = \"</script>\";");
        assertThatThrownBy(() -> SingleFile.write(new SingleFile.Options(folder, dir.resolve("a.html"), script)))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("</script");
    }

    private static byte[] jar(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : entries.keySet().stream().sorted().toList()) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(entries.get(name).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private void write(String name, String content) throws Exception {
        Path file = folder.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
