package incari.app.desktop.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * Remote control for the screenshot-diff harness ({@code tools/webbuild}'s
 * {@code ScreenshotDiff}): the harness renders the same scenes here, with Mojang's OpenGL backend,
 * and in the browser, and compares the two. It drives this game over a line protocol on a loopback
 * port, the way it drives the browser over the DevTools protocol:
 *
 * <ul>
 *   <li>{@code state}: {@code world} when a world is shown with no screen open, {@code screen} with
 *       a screen over it, {@code menu} otherwise
 *   <li>{@code key <scancode> <keycode> <down|up>}: a key, as SDL reports it
 *   <li>{@code move <x> <y>} and {@code click <x> <y>}: the pointer, in fractions of the window
 *   <li>{@code type <text>}: text typed into whatever has the focus, as SDL's text input reports it
 *   <li>{@code shot <path>}: the main render target, as a PNG (what the window shows, before
 *       scaling); answers {@code ok <width> <height>}
 * </ul>
 *
 * Every answer is one line, {@code ok ...} or {@code error <why>}. Off unless
 * {@code -Dincari.sceneAgent=<port>}; development tooling, never in a build players get.
 */
public final class SceneAgent {

    private final Minecraft minecraft;
    private @Nullable SdlInjector input;

    private SceneAgent(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** Listens on the port {@code incari.sceneAgent} names, if it's set, on a thread of its own. */
    public static void startIfRequested() {
        String port = System.getProperty("incari.sceneAgent");
        if (port == null || port.isBlank()) {
            return;
        }
        Thread.ofPlatform().name("Incari scene agent").daemon().start(() -> {
            try (ServerSocket server = new ServerSocket(Integer.parseInt(port), 1, InetAddress.getLoopbackAddress())) {
                Minecraft minecraft = waitForGame();
                SceneAgent agent = new SceneAgent(minecraft);
                while (true) {
                    try (Socket socket = server.accept()) {
                        agent.serve(socket);
                    }
                }
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("Scene agent").error("The scene agent stopped", e);
            }
        });
    }

    private void serve(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            String answer;
            try {
                answer = "ok" + run(line.strip().split(" ", -1));
            } catch (RuntimeException | IOException e) {
                answer = "error " + e.toString().replace('\n', ' ');
            }
            out.println(answer);
        }
    }

    private String run(String[] command) throws IOException {
        return switch (command[0]) {
            case "state" -> " " + onGameThread(this::state);
            case "key" -> {
                injector().key(Integer.parseInt(command[1]), Integer.parseInt(command[2]), command[3].equals("down"));
                yield "";
            }
            case "move", "click" -> {
                float[] size = onGameThread(() -> new float[] {
                    minecraft.getWindow().getScreenWidth(),
                    minecraft.getWindow().getScreenHeight()
                });
                SdlInjector injector = injector();
                injector.moveTo(Float.parseFloat(command[1]) * size[0], Float.parseFloat(command[2]) * size[1]);
                if (command[0].equals("click")) {
                    injector.mouseButton(1, true);
                    injector.mouseButton(1, false);
                }
                yield "";
            }
            case "type" -> {
                injector().text(String.join(" ", java.util.Arrays.copyOfRange(command, 1, command.length)));
                yield "";
            }
            case "shot" -> " " + shot(Path.of(command[1]));
            default -> throw new IllegalArgumentException("Unknown command " + command[0]);
        };
    }

    private String state() {
        if (minecraft.level == null || minecraft.player == null) {
            return "menu";
        }
        return minecraft.gui.screen() == null ? "world" : "screen";
    }

    private String shot(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        CompletableFuture<String> written = new CompletableFuture<>();
        onGameThread(() -> {
            Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
                try (NativeImage owned = image) {
                    owned.writeToFile(file);
                    written.complete(owned.getWidth() + " " + owned.getHeight());
                } catch (IOException | RuntimeException e) {
                    written.completeExceptionally(e);
                }
            });
            return "";
        });
        try {
            return written.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IOException("The screenshot wasn't written", e);
        }
    }

    private SdlInjector injector() {
        SdlInjector current = input;
        if (current == null) {
            int windowId = onGameThread(
                    () -> SDLVideo.SDL_GetWindowID(minecraft.getWindow().handle()));
            current = new SdlInjector(windowId);
            current.focus();
            input = current;
        }
        return current;
    }

    private <T> T onGameThread(java.util.function.Supplier<T> task) {
        try {
            return minecraft.submit(task).get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IllegalStateException("A scene agent command failed on the game thread", e);
        }
    }

    private static Minecraft waitForGame() {
        while (true) {
            Minecraft instance = Minecraft.getInstance();
            if (instance != null) {
                return instance;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
    }
}
