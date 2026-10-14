package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The benchmark's script, as {@code app-web/benchmark/benchmark.txt} writes it: steps for
 * the server before anyone joins, steps once the page has joined, and named scenarios, each a
 * list of steps with measured stretches in it.
 *
 * <pre>
 * server
 *     rcon:gamerule advance_time false
 * setup
 *     rcon:gamemode spectator IncariBench
 * scenario flythrough  A straight flight over fresh terrain
 *     rcon:spectate @e[tag=incari_camera,limit=1] IncariBench
 *     measure:60
 * </pre>
 *
 * Steps: {@code rcon:<command>} (a server command; {@code rcon*<n>:<command>} runs it {@code n}
 * times, to summon a crowd), {@code rconwait:<command> => <text>} (repeat a
 * command until its reply contains the text, for up to five minutes), {@code sleep:<ms>},
 * {@code key:<chord>}, {@code move:<x>,<y>} (fractions of the window), {@code shot:<name>} (a
 * screenshot in {@code build/benchmark}) and, in a scenario only,
 * {@code measure:<seconds>}: a stretch whose frames count towards the scenario's figures. A
 * scenario's figures cover all its measured stretches together. A {@code #} starts a comment line.
 * In singleplayer ({@code app-web/benchmark/singleplayer.txt}) there's no server to reach: the
 * runner types each command into the game's chat, and a {@code rconwait} reads its answer from the
 * page's console.
 */
record BenchmarkScript(List<Step> server, List<Step> setup, List<Scenario> scenarios) {

    /** One step: its kind (the part before the colon), its value, and its line, for messages. */
    record Step(String kind, String value, int line) {}

    record Scenario(String name, String description, List<Step> steps) {}

    private static final Set<String> KINDS = Set.of("rcon", "rconwait", "sleep", "key", "move", "shot", "measure");

    static BenchmarkScript read(Path file) throws IOException {
        return parse(Files.readAllLines(file), file.toString());
    }

    static BenchmarkScript parse(List<String> lines, String source) {
        List<Step> server = new ArrayList<>();
        List<Step> setup = new ArrayList<>();
        List<Scenario> scenarios = new ArrayList<>();
        @Nullable List<Step> current = null;
        String section = "";
        String name = "";
        String description = "";
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.strip();
            int number = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(raw.charAt(0))) {
                if (section.equals("scenario") && current != null) {
                    scenarios.add(scenario(name, description, current, source));
                }
                String[] words = line.split("\\s+", 3);
                section = words[0];
                switch (section) {
                    case "server" -> current = server;
                    case "setup" -> current = setup;
                    case "scenario" -> {
                        if (words.length < 2) {
                            throw error(source, number, "a scenario needs a name");
                        }
                        name = words[1];
                        description = words.length > 2 ? words[2] : "";
                        if (!name.matches("[a-z0-9_]+")) {
                            throw error(source, number, "scenario names are [a-z0-9_]+: '" + name + "'");
                        }
                        String taken = name;
                        if (scenarios.stream()
                                .anyMatch(scenario -> scenario.name().equals(taken))) {
                            throw error(source, number, "scenario " + name + " is named twice");
                        }
                        current = new ArrayList<>();
                    }
                    default -> throw error(source, number, "expected server, setup or scenario, not '" + line + "'");
                }
                continue;
            }
            if (current == null) {
                throw error(source, number, "a step outside a section");
            }
            int colon = line.indexOf(':');
            String kind = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            int times = 1;
            if (kind.matches("rcon\\*\\d+")) {
                times = Integer.parseInt(kind.substring("rcon*".length()));
                kind = "rcon";
            }
            if (!KINDS.contains(kind)) {
                throw error(source, number, "unknown step '" + kind + "'");
            }
            if (kind.equals("measure") && !section.equals("scenario")) {
                throw error(source, number, "only scenarios measure");
            }
            if (kind.equals("measure") && !value.matches("\\d+")) {
                throw error(source, number, "measure takes whole seconds: '" + value + "'");
            }
            if (kind.equals("rconwait") && !value.contains(" => ")) {
                throw error(source, number, "rconwait needs '<command> => <text>'");
            }
            if (!section.equals("scenario")
                    && !section.equals("setup")
                    && !kind.startsWith("rcon")
                    && !kind.equals("sleep")) {
                throw error(source, number, "the server section only runs server commands and sleeps");
            }
            for (int n = 0; n < times; n++) {
                current.add(new Step(kind, value, number));
            }
        }
        if (section.equals("scenario") && current != null) {
            scenarios.add(scenario(name, description, current, source));
        }
        if (scenarios.isEmpty()) {
            throw new CliException(source + ": no scenarios");
        }
        return new BenchmarkScript(List.copyOf(server), List.copyOf(setup), List.copyOf(scenarios));
    }

    private static Scenario scenario(String name, String description, List<Step> steps, String source) {
        if (steps.stream().noneMatch(step -> step.kind().equals("measure"))) {
            throw new CliException(source + ": scenario " + name + " measures nothing");
        }
        return new Scenario(name, description, List.copyOf(steps));
    }

    private static CliException error(String source, int line, String message) {
        return new CliException(source + ":" + line + ": " + message);
    }
}
