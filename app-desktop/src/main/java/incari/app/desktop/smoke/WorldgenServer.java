package incari.app.desktop.smoke;

import incari.client.startup.CriterionCodecs;
import incari.client.world.BiomeCells;
import incari.client.world.DensityBranches;
import incari.client.world.Heights;
import incari.client.world.SurfaceWrites;
import incari.platform.api.Platforms;
import incari.platform.desktop.DesktopPlatform;
import incari.platform.desktop.DesktopStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's dedicated server on the desktop platform, with the browser build's world generation
 * changes (ADR 0041) on or off ({@code -Dincari.worldgen=on|off}): run twice on one seed, the
 * worlds it saves must hold the same chunks ({@code tools/worldgen/compare.py} compares the region
 * files). {@code -Dincari.worldgen.script=<file>} types its lines into the server's console, a
 * line {@code sleep <seconds>} waiting instead. Arguments are the server's own
 * ({@code --nogui --universe <folder>}).
 */
public final class WorldgenServer {

    private WorldgenServer() {}

    public static void main(String[] args) throws Exception {
        boolean on = "on".equals(System.getProperty("incari.worldgen", "off"));
        Logger logger = LoggerFactory.getLogger("Platform");
        DesktopStorage storage = new DesktopStorage(".", "assets", null);
        Platforms.install(new DesktopPlatform(storage, (level, message, error) -> logger.info(message, error), false));
        SurfaceWrites.setEnabled(on);
        BiomeCells.setEnabled(on);
        BiomeCells.setVerify(on && Boolean.getBoolean("incari.worldgen.verify"));
        Heights.setEnabled(on);
        CriterionCodecs.setEnabled(on);
        DensityBranches.setEnabled(on);
        logger.info("[Incari] World generation changes (ADRs 0041, 0042): {}", on ? "on" : "off");
        String script = System.getProperty("incari.worldgen.script");
        if (script != null) {
            typeInto(List.copyOf(Files.readAllLines(Path.of(script))));
        }
        net.minecraft.server.Main.main(args);
    }

    /** Types {@code lines} into the server's console, from a thread of its own. */
    private static void typeInto(List<String> lines) throws IOException {
        PipedOutputStream console = new PipedOutputStream();
        System.setIn(new PipedInputStream(console));
        Thread typist = new Thread(() -> type(lines, console), "Worldgen script");
        typist.setDaemon(true);
        typist.start();
    }

    private static void type(List<String> lines, OutputStream console) {
        try {
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("sleep ")) {
                    Thread.sleep(Long.parseLong(line.substring(6).trim()) * 1000);
                    continue;
                }
                console.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                console.flush();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[Incari] The worldgen script stopped: " + e);
        }
    }
}
