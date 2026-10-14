package incari.platform.api;

import java.io.File;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** The parts of the operating system around the window: clipboard, links, files, message boxes. */
public interface PlatformShell {

    /**
     * The clipboard's text.
     *
     * @return the text, empty if the clipboard holds none, or null if it couldn't be read. In the
     *     browser this is the text of the last paste event: pages can't read the clipboard at will.
     */
    @Nullable
    String clipboardText();

    /** Replaces the clipboard's contents with {@code text}. Returns false if that failed. */
    boolean setClipboardText(String text);

    /**
     * Puts a PNG image on the clipboard, without waiting (a screenshot).
     *
     * @return false if this platform can't put images on the clipboard; true if it's on its way
     */
    default boolean setClipboardImage(byte[] png) {
        return false;
    }

    /**
     * Opens a link or a local folder in the user's default application, without waiting for it.
     * May be called from any thread. Failures are logged by the platform, never thrown: the game
     * never depends on the result.
     */
    void open(URI uri);

    /**
     * Shows the player one of the game's files or folders (a screenshot, the resource pack
     * folder), without waiting. The desktop opens it in the system's file manager. A page can't
     * show the player its files: the browser downloads a file, and for a folder opens the file
     * picker, whose files reach the game as if dropped on its window. Failures are logged, never
     * thrown.
     *
     * @param path an absolute path in the platform's file system
     */
    default void openPath(String path) {
        open(new File(path).toURI());
    }

    /**
     * Whether {@link #openPath} shows the player a folder's contents (a file manager). A page can't:
     * a folder the player wants to see, a world, is better handed over as a {@link #download}.
     */
    default boolean showsFolders() {
        return true;
    }

    /**
     * Hands the player a file to keep, without waiting: the browser downloads it under {@code
     * fileName}. A platform that shows folders ({@link #showsFolders}) has no need for it and
     * logs that it was asked.
     */
    default void download(String fileName, byte[] bytes) {
        System.err.println("[Incari] This platform shows folders; not downloading " + fileName);
    }

    /**
     * Shows a blocking error dialog, for failures the game reports before or instead of its own
     * UI (a crash at startup).
     *
     * @param askToContinue whether to offer "Continue"/"Quit" instead of just "OK"
     * @return true if the player chose to continue; false if they didn't, if there was no choice
     *     to make, or if no dialog could be shown
     */
    boolean showErrorDialog(String title, String message, boolean askToContinue);
}
