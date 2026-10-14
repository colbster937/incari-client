package incari.tools.webbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a heap snapshot holds, summed by type: each node's self size and count, grouped by its
 * kind and name. WebAssembly GC objects are named by their type in the module's name section, so a
 * development build's snapshot sums the game's heap by Java class. Streams the file: a gigabyte
 * heap's snapshot is several gigabytes of JSON, and only the node table and the strings are read.
 *
 * <p>{@code java HeapSnapshotSummary.java <file.heapsnapshot> [rows]}, or through the benchmark's
 * {@code --heap-snapshot}, which writes the summary beside the snapshot.
 */
final class HeapSnapshotSummary {

    private HeapSnapshotSummary() {}

    public static void main(String[] args) throws IOException {
        System.out.print(summarize(Path.of(args[0]), args.length > 1 ? Integer.parseInt(args[1]) : 80));
    }

    /** The summary as text: a total, then the {@code rows} largest groups. */
    static String summarize(Path file, int rows) throws IOException {
        try (Reader reader = new BufferedReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 1 << 20)) {
            return summarize(new Scanner(reader), rows);
        }
    }

    private static String summarize(Scanner in, int rows) throws IOException {
        // {"snapshot":{"meta":{...},"node_count":N,...},"nodes":[...],"edges":[...],...,"strings":[...]}
        String head = in.textUntil("\"nodes\":[", true);
        JsonObject snapshot = JsonParser.parseString(head.substring(0, head.lastIndexOf(',')) + "}")
                .getAsJsonObject()
                .getAsJsonObject("snapshot");
        JsonObject meta = snapshot.getAsJsonObject("meta");
        List<String> fields = strings(meta.getAsJsonArray("node_fields"));
        List<String> kinds = strings(meta.getAsJsonArray("node_types").get(0).getAsJsonArray());
        int width = fields.size();
        int typeField = fields.indexOf("type");
        int nameField = fields.indexOf("name");
        int sizeField = fields.indexOf("self_size");
        int count = snapshot.get("node_count").getAsInt();
        int[] types = new int[count];
        int[] names = new int[count];
        long[] sizes = new long[count];
        for (int node = 0; node < count; node++) {
            for (int field = 0; field < width; field++) {
                long value = in.number();
                if (field == typeField) {
                    types[node] = (int) value;
                } else if (field == nameField) {
                    names[node] = (int) value;
                } else if (field == sizeField) {
                    sizes[node] = value;
                }
            }
        }
        in.textUntil("\"strings\":[", false);
        List<String> strings = new ArrayList<>();
        String string;
        while ((string = in.string()) != null) {
            strings.add(string);
        }

        final class Group {
            final String kind;
            final String name;
            long bytes;
            long objects;

            Group(String kind, String name) {
                this.kind = kind;
                this.name = name;
            }
        }
        Map<String, Group> groups = new HashMap<>();
        long total = 0;
        for (int node = 0; node < count; node++) {
            String kind = kinds.get(types[node]);
            String name = names[node] < strings.size() ? strings.get(names[node]) : "?";
            if (name.length() > 120) {
                name = name.substring(0, 120) + "...";
            }
            // Strings and numbers are many and each named by its contents: one group per kind.
            if (kind.contains("string") || kind.equals("number") || kind.equals("code")) {
                name = "";
            }
            String key = kind + '\u0000' + name;
            String groupName = name;
            Group group = groups.computeIfAbsent(key, k -> new Group(kind, groupName));
            group.bytes += sizes[node];
            group.objects++;
            total += sizes[node];
        }
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%,d nodes, %.1f MB of self sizes%n%n", count, total / 1048576.0));
        out.append(String.format(Locale.ROOT, "%10s %12s  %-12s %s%n", "MB", "objects", "kind", "name"));
        for (Group group : sorted.subList(0, Math.min(rows, sorted.size()))) {
            out.append(String.format(
                    Locale.ROOT,
                    "%10.2f %,12d  %-12s %s%n",
                    group.bytes / 1048576.0,
                    group.objects,
                    group.kind,
                    group.name));
        }
        return out.toString();
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>(array.size());
        array.forEach(element -> out.add(element.getAsString()));
        return out;
    }

    /** Just enough JSON reading for the snapshot's layout, one character at a time. */
    private static final class Scanner {
        private final Reader reader;
        private int peeked = -2;

        Scanner(Reader reader) {
            this.reader = reader;
        }

        private int read() throws IOException {
            if (peeked != -2) {
                int c = peeked;
                peeked = -2;
                return c;
            }
            return reader.read();
        }

        /**
         * Reads up to and including {@code marker}; what was read if {@code keep}, else "" (the
         * edges skipped on the way are gigabytes). The markers are JSON keys, which numbers and the
         * snapshot's head can't contain partly, so a mismatch simply starts the match again.
         */
        String textUntil(String marker, boolean keep) throws IOException {
            StringBuilder text = new StringBuilder();
            int matched = 0;
            int c;
            while ((c = read()) != -1) {
                if (keep) {
                    text.append((char) c);
                }
                if (c == marker.charAt(matched)) {
                    matched++;
                    if (matched == marker.length()) {
                        return text.toString();
                    }
                } else {
                    matched = c == marker.charAt(0) ? 1 : 0;
                }
            }
            throw new IOException("Not a heap snapshot: no " + marker);
        }

        /** The next number in an array of numbers. */
        long number() throws IOException {
            int c = read();
            while (c == ',' || c == ' ' || c == '\n' || c == '\r') {
                c = read();
            }
            boolean negative = c == '-';
            if (negative) {
                c = read();
            }
            if (c < '0' || c > '9') {
                throw new IOException("Expected a number, found '" + (char) c + "'");
            }
            long value = 0;
            while (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                c = read();
            }
            peeked = c;
            return negative ? -value : value;
        }

        /** The next string in an array of strings; null at the array's end. */
        @org.jspecify.annotations.Nullable
        String string() throws IOException {
            int c = read();
            while (c == ',' || c == ' ' || c == '\n' || c == '\r') {
                c = read();
            }
            if (c == ']' || c == -1) {
                return null;
            }
            if (c != '"') {
                throw new IOException("Expected a string, found '" + (char) c + "'");
            }
            StringBuilder out = new StringBuilder();
            while ((c = read()) != '"') {
                if (c == -1) {
                    throw new IOException("A string runs past the end");
                }
                if (c == '\\') {
                    int escaped = read();
                    switch (escaped) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            char[] hex = new char[4];
                            for (int i = 0; i < 4; i++) {
                                hex[i] = (char) read();
                            }
                            out.append((char) Integer.parseInt(new String(hex), 16));
                        }
                        default -> out.append((char) escaped);
                    }
                } else {
                    out.append((char) c);
                }
            }
            return out.toString();
        }
    }
}
