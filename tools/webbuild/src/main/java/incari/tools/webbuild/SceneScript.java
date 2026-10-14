package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The screenshot-diff harness's scenes, as {@code app-web/screenshot-scenes/scenes.txt}
 * writes them: a threshold, setup steps run once after the player joins, and named scenes, each a
 * list of steps ending in one or more screenshots.
 *
 * <pre>
 * threshold 0.90
 * setup
 *     rcon:tick freeze
 *     key:F1
 * scene terrain  Overworld terrain at noon
 *     rcon:tp IncariShot 10 90 10 45 20
 *     settle
 *     shot:terrain
 * </pre>
 *
 * Steps, the same for the desktop game and the browser: {@code rcon:<command>} (a server
 * command), {@code key:<chord>} ({@code KeyboardEvent.code} names, as browser check steps write
 * them), {@code move:<x>,<y>} and {@code click:<x>,<y>} (fractions of the window),
 * {@code sleep:<ms>}, {@code settle} (wait until the picture stops changing) and
 * {@code shot:<name>}. A {@code #} starts a comment line.
 */
record SceneScript(double threshold, List<Step> setup, List<Scene> scenes) {

    /** One step: its kind (the part before the colon), its value, and its line, for messages. */
    record Step(String kind, String value, int line) {}

    record Scene(String name, String description, List<Step> steps) {
        /** The names of the screenshots the scene takes, in order. */
        List<String> shots() {
            return steps.stream()
                    .filter(step -> step.kind().equals("shot"))
                    .map(Step::value)
                    .toList();
        }
    }

    private static final Set<String> KINDS = Set.of("rcon", "key", "move", "click", "sleep", "settle", "shot");

    static SceneScript read(Path file) throws IOException {
        return parse(Files.readAllLines(file), file.toString());
    }

    static SceneScript parse(List<String> lines, String source) {
        double threshold = Double.NaN;
        List<Step> setup = new ArrayList<>();
        List<Scene> scenes = new ArrayList<>();
        @Nullable List<Step> current = null;
        boolean inSetup = false;
        String name = "";
        String description = "";
        Set<String> shots = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.strip();
            int number = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            boolean indented = Character.isWhitespace(raw.charAt(0));
            if (!indented) {
                if (current != null && !inSetup) {
                    scenes.add(new Scene(name, description, List.copyOf(current)));
                }
                String[] words = line.split("\\s+", 3);
                switch (words[0]) {
                    case "threshold" -> {
                        threshold = Double.parseDouble(words[1]);
                        current = null;
                        inSetup = false;
                    }
                    case "setup" -> {
                        current = setup;
                        inSetup = true;
                    }
                    case "scene" -> {
                        if (words.length < 2) {
                            throw error(source, number, "a scene needs a name");
                        }
                        name = words[1];
                        description = words.length > 2 ? words[2] : "";
                        if (scenes.stream().anyMatch(scene -> scene.name().equals(words[1]))) {
                            throw error(source, number, "scene " + name + " is named twice");
                        }
                        current = new ArrayList<>();
                        inSetup = false;
                    }
                    default -> throw error(source, number, "expected threshold, setup or scene, not '" + line + "'");
                }
                continue;
            }
            if (current == null) {
                throw error(source, number, "a step outside setup or a scene");
            }
            int colon = line.indexOf(':');
            String kind = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (!KINDS.contains(kind)) {
                throw error(source, number, "unknown step '" + kind + "'");
            }
            if (kind.equals("shot")) {
                if (inSetup) {
                    throw error(source, number, "setup takes no screenshots");
                }
                if (!value.matches("[a-z0-9_]+") || !shots.add(value)) {
                    throw error(source, number, "shot names are unique and [a-z0-9_]+: '" + value + "'");
                }
            }
            current.add(new Step(kind, value, number));
        }
        if (current != null && !inSetup) {
            scenes.add(new Scene(name, description, List.copyOf(current)));
        }
        if (Double.isNaN(threshold) || threshold <= 0 || threshold > 1) {
            throw new CliException(source + ": needs a 'threshold <ssim>' between 0 and 1");
        }
        return new SceneScript(threshold, List.copyOf(setup), List.copyOf(scenes));
    }

    private static CliException error(String source, int line, String message) {
        return new CliException(source + ":" + line + ": " + message);
    }
}
