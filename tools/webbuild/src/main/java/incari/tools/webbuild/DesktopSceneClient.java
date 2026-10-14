package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * The desktop game (Mojang's OpenGL backend) as the harness's reference: started from the launch
 * file {@code :app-desktop:sceneLaunch} writes, with a fresh game directory holding the pinned
 * options, straight into the harness's server (quick play), and driven through app-desktop's
 * {@code SceneAgent} on a loopback port.
 */
final class DesktopSceneClient implements SceneClient {

    private final Process process;
    private final Path java;
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    private DesktopSceneClient(Process process, Path java, Socket socket) throws IOException {
        this.process = process;
        this.java = java;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    /**
     * Starts the game.
     *
     * @param launch {@code :app-desktop:sceneLaunch}'s file
     * @param gameDirectory a fresh directory, already holding {@code options.txt}
     * @param server the server, as {@code host:port}
     */
    static DesktopSceneClient start(
            Path launch, Path gameDirectory, String player, String server, int agentPort, int width, int height)
            throws IOException, InterruptedException {
        return start(launch, gameDirectory, player, server, agentPort, width, height, List.of());
    }

    /** As {@link #start(Path, Path, String, String, int, int, int)}, with {@code extraJvm} options for the game's JVM. */
    static DesktopSceneClient start(
            Path launch,
            Path gameDirectory,
            String player,
            String server,
            int agentPort,
            int width,
            int height,
            List<String> extraJvm)
            throws IOException, InterruptedException {
        @Nullable String java = null;
        @Nullable String main = null;
        List<String> jvm = new ArrayList<>();
        List<String> classpath = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        for (String line : Files.readAllLines(launch)) {
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String value = line.substring(space + 1);
            switch (line.substring(0, space)) {
                case "java" -> java = value;
                case "jvm" -> jvm.add(value);
                case "classpath" -> classpath.add(value);
                case "main" -> main = value;
                case "arg" -> arguments.add(value);
                default -> throw new CliException(launch + ": unknown line '" + line + "'");
            }
        }
        if (java == null || main == null) {
            throw new CliException(launch + " names no java or main class; run ./gradlew :app-desktop:sceneLaunch");
        }
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvm);
        command.addAll(extraJvm);
        command.add("-Dincari.sceneAgent=" + agentPort);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(main);
        command.addAll(arguments);
        command.addAll(List.of(
                "--gameDir", gameDirectory.toAbsolutePath().toString(),
                "--username", player,
                "--width", Integer.toString(width),
                "--height", Integer.toString(height),
                "--quickPlayMultiplayer", server));
        System.out.println("==> Starting the desktop game (Mojang's OpenGL backend)");
        Process process = new ProcessBuilder(command)
                .directory(gameDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(gameDirectory.resolve("game-output.txt").toFile())
                .start();
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (true) {
            try {
                return new DesktopSceneClient(
                        process, Path.of(java), new Socket(InetAddress.getLoopbackAddress(), agentPort));
            } catch (IOException e) {
                if (!process.isAlive() || System.nanoTime() > until) {
                    process.destroyForcibly();
                    throw new IOException(
                            "The desktop game's scene agent didn't answer; see "
                                    + gameDirectory.resolve("game-output.txt"),
                            e);
                }
                Thread.sleep(500);
            }
        }
    }

    @Override
    public String name() {
        return "desktop";
    }

    /**
     * Runs {@code jcmd} against the game's JVM with {@code command} (a Flight Recorder recording's
     * start and stop, for the benchmark's allocation profile, ADR 0025); its output.
     */
    String jcmd(String... command) throws IOException, InterruptedException {
        List<String> line = new ArrayList<>();
        Path parent = java.getParent();
        line.add(parent == null ? "jcmd" : parent.resolve("jcmd").toString());
        line.add(Long.toString(process.pid()));
        line.addAll(List.of(command));
        Process jcmd = new ProcessBuilder(line).redirectErrorStream(true).start();
        String output = new String(jcmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (jcmd.waitFor() != 0) {
            throw new IOException("jcmd " + String.join(" ", command) + " failed: " + output);
        }
        return output;
    }

    @Override
    public void awaitWorld() throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        String state = "";
        while (System.nanoTime() < until) {
            state = send("state");
            if (state.equals("world")) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IOException("The desktop game isn't in the world (it's at '" + state + "')");
    }

    @Override
    public void key(List<String> chord) throws IOException {
        for (String code : chord) {
            Keys.SdlKey key = Keys.sdl(code);
            send("key " + key.scancode() + " " + key.keycode() + " down");
        }
        for (String code : chord.reversed()) {
            Keys.SdlKey key = Keys.sdl(code);
            send("key " + key.scancode() + " " + key.keycode() + " up");
        }
    }

    @Override
    public void move(double x, double y) throws IOException {
        send("move " + x + " " + y);
    }

    @Override
    public void click(double x, double y) throws IOException {
        send("click " + x + " " + y);
    }

    @Override
    public BufferedImage shot(Path file) throws IOException {
        send("shot " + file.toAbsolutePath());
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("The desktop game wrote no readable PNG at " + file);
        }
        return image;
    }

    private String send(String command) throws IOException {
        out.println(command);
        String answer = in.readLine();
        if (answer == null) {
            throw new IOException("The desktop game closed its scene agent (did it crash?)");
        }
        if (!answer.startsWith("ok")) {
            throw new IOException("The desktop game's scene agent: " + command + ": " + answer);
        }
        return answer.substring(2).strip();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
