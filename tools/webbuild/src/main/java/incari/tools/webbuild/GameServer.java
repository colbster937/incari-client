package incari.tools.webbuild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import incari.tools.build.CliException;
import incari.tools.build.Downloader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A vanilla dedicated server for the screenshot-diff harness, with a fresh world every run: the
 * server jar from Mojang's version metadata (checked against its SHA-1, kept in {@code work/cache},
 * never in the repository), the committed server settings plus this run's ports and a random RCON
 * password, and an RCON connection to set each scene up.
 */
final class GameServer implements AutoCloseable {

    private final Process process;
    private final Rcon rcon;
    private final PrintWriter log;

    private GameServer(Process process, Rcon rcon, PrintWriter log) {
        this.process = process;
        this.rcon = rcon;
        this.log = log;
    }

    /**
     * Starts the server in {@code directory}, deleting any world there first.
     *
     * @param versionJson the game version's metadata, which names the server jar
     * @param properties the committed {@code server.properties}; ports and RCON are added here
     */
    static GameServer start(Path versionJson, Path cache, Path directory, Path properties, int port, int rconPort)
            throws IOException, InterruptedException {
        Path jar = serverJar(versionJson, cache);
        Chromium.deleteRecursively(directory.resolve("world"));
        Files.createDirectories(directory);
        String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(12));
        List<String> settings = new ArrayList<>(Files.readAllLines(properties));
        settings.add("server-ip=127.0.0.1");
        settings.add("server-port=" + port);
        settings.add("enable-rcon=true");
        settings.add("rcon.port=" + rconPort);
        settings.add("rcon.password=" + password);
        Files.write(directory.resolve("server.properties"), settings);
        Files.writeString(directory.resolve("eula.txt"), "eula=true\n");

        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(
                        java, "-Xmx2G", "-jar", jar.toAbsolutePath().toString(), "nogui")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        PrintWriter log = new PrintWriter(Files.newBufferedWriter(directory.resolve("server-output.txt")), true);
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader out =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    log.println(line);
                    if (line.contains("Done (")) {
                        ready.complete(null);
                    }
                }
            } catch (IOException e) {
                // The server stopped; ready fails below if it never started.
            } finally {
                ready.completeExceptionally(new IOException("The server stopped; see its output in " + directory));
            }
        });
        System.out.println("==> Starting a vanilla server on port " + port + " (a fresh world takes a minute)");
        try {
            ready.get(5, TimeUnit.MINUTES);
            // The remote console starts listening just after the server says it's done.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try {
                    return new GameServer(process, Rcon.connect("127.0.0.1:" + rconPort, password), log);
                } catch (IOException e) {
                    if (System.nanoTime() > until) {
                        throw e;
                    }
                    Thread.sleep(250);
                }
            }
        } catch (Exception e) {
            process.destroyForcibly();
            log.close();
            throw new IOException("The game server didn't start: " + e.getMessage(), e);
        }
    }

    /** Runs a server command; its reply. */
    String command(String command) throws IOException {
        return rcon.command(command).strip();
    }

    /** Waits until {@code player} is online; false if they aren't within {@code seconds}. */
    boolean awaitPlayer(String player, int seconds) throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (command("list").contains(player)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static Path serverJar(Path versionJson, Path cache) throws IOException, InterruptedException {
        JsonObject server = JsonParser.parseString(Files.readString(versionJson))
                .getAsJsonObject()
                .getAsJsonObject("downloads")
                .getAsJsonObject("server");
        if (server == null) {
            throw new CliException(versionJson + " names no server jar");
        }
        Path jar = cache.resolve("server.jar");
        new Downloader()
                .fetch(
                        URI.create(server.get("url").getAsString()),
                        server.get("sha1").getAsString(),
                        jar);
        return jar;
    }

    @Override
    public void close() throws IOException {
        try {
            rcon.command("stop");
        } catch (IOException e) {
            // Stopping anyway.
        }
        rcon.close();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        log.close();
    }
}
