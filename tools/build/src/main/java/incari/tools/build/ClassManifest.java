package incari.tools.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whole-file changes to the vendor tree: classes Incari deletes outright, and classes it
 * replaces with its own source file.
 *
 * <p>These don't go through patches on purpose. A diff that deletes or rewrites a whole
 * Mojang file has to contain every one of its lines, which would republish them. A manifest
 * line names the class and contains no Mojang code at all.
 *
 * <p>Format of {@code patches/classes.txt}, one entry per line, {@code #} starts a comment:
 * <pre>
 * delete  net.minecraft.client.telemetry.ClientTelemetryManager
 * replace com.mojang.blaze3d.vertex.ByteBufferBuilder  render-webgl2/src/vendor/java/com/mojang/blaze3d/vertex/ByteBufferBuilder.java
 * </pre>
 * Replacement paths are relative to the repo root.
 */
public record ClassManifest(List<Entry> entries) {

    public static final String FILE_NAME = "classes.txt";

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    public sealed interface Entry {
        String className();

        /** Path of the class's source file, relative to the client tree root. */
        default String treePath() {
            return Workspace.CLIENT_SOURCE_DIR + "/" + className().replace('.', '/') + ".java";
        }
    }

    public record Delete(String className) implements Entry {}

    public record Replace(String className, String source) implements Entry {}

    public static ClassManifest load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ClassManifest(List.of());
        }
        return parse(Files.readAllLines(file), file.toString());
    }

    static ClassManifest parse(List<String> lines, String origin) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (content.isEmpty()) {
                continue;
            }
            String where = origin + ":" + (i + 1);
            String[] parts = content.split("\\s+", -1);
            Entry entry =
                    switch (parts[0]) {
                        case "delete" -> {
                            expect(parts.length == 2, where, "expected: delete <class>");
                            yield new Delete(className(parts[1], where));
                        }
                        case "replace" -> {
                            expect(parts.length == 3, where, "expected: replace <class> <source path>");
                            String source = parts[2];
                            expect(
                                    !source.startsWith("/") && !source.contains("..") && !source.startsWith("work/"),
                                    where,
                                    "replacement source must be a repo-relative path outside work/");
                            yield new Replace(className(parts[1], where), source);
                        }
                        default -> throw new CliException(where + ": unknown action '" + parts[0] + "'");
                    };
            expect(seen.add(entry.className()), where, entry.className() + " is listed twice");
            entries.add(entry);
        }
        return new ClassManifest(List.copyOf(entries));
    }

    /** Tree paths this manifest owns. Patches must not touch them. */
    public Set<String> treePaths() {
        Set<String> paths = new HashSet<>();
        for (Entry entry : entries) {
            paths.add(entry.treePath());
        }
        return paths;
    }

    private static String className(String value, String where) {
        expect(
                CLASS_NAME.matcher(value).matches() && !value.contains("$"),
                where,
                "'" + value + "' is not a top-level class name (nested classes can't be replaced alone)");
        return value;
    }

    private static void expect(boolean condition, String where, String message) {
        if (!condition) {
            throw new CliException(where + ": " + message);
        }
    }
}
