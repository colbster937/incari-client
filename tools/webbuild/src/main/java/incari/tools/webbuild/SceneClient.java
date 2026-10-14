package incari.tools.webbuild;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A game the screenshot-diff harness drives through its scenes: the desktop game over its scene
 * agent ({@link DesktopSceneClient}) or the browser build over the DevTools protocol
 * ({@link BrowserSceneClient}). Both take the same steps, so both render the same scene.
 */
interface SceneClient extends AutoCloseable {

    /** "desktop" or "browser": the directory its screenshots go to. */
    String name();

    /** Waits until the player is in the world with no screen open (the server already lists them). */
    void awaitWorld() throws IOException, InterruptedException;

    /** Presses the keys of a chord in order and releases them in reverse ({@code KeyboardEvent.code} names). */
    void key(List<String> chord) throws IOException, InterruptedException;

    /** Moves the pointer to a position in fractions of the window. */
    void move(double x, double y) throws IOException, InterruptedException;

    /** Moves the pointer there and clicks the left button. */
    void click(double x, double y) throws IOException, InterruptedException;

    /** What the game shows now, saved as a PNG at {@code file}. */
    BufferedImage shot(Path file) throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
