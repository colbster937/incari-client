package incari.platform.desktop;

import incari.platform.api.PlatformLog;
import incari.platform.api.PlatformShell;
import java.net.URI;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDLMisc;
import org.lwjgl.sdl.SDL_MessageBoxButtonData;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.system.MemoryStack;

/** Clipboard, links and message boxes through SDL3. */
final class SdlShell implements PlatformShell {

    private static final int CONTINUE = 1;
    private static final int QUIT = 2;

    private final PlatformLog log;

    SdlShell(PlatformLog log) {
        this.log = log;
    }

    @Override
    public @Nullable String clipboardText() {
        String text = SDLClipboard.SDL_GetClipboardText();
        if (text == null) {
            log.warn("Couldn't read the clipboard: " + SdlWindow.sdlError());
        }
        return text;
    }

    @Override
    public boolean setClipboardText(String text) {
        boolean set = SDLClipboard.SDL_SetClipboardText(text);
        if (!set) {
            log.warn("Couldn't set the clipboard: " + SdlWindow.sdlError());
        }
        return set;
    }

    @Override
    public void open(URI uri) {
        // Opening a browser or file manager can take a while on some desktops: never on the
        // render thread.
        Thread.ofVirtual().name("Incari-open-uri").start(() -> {
            if (!SDLMisc.SDL_OpenURL(uri.toString())) {
                log.warn("Couldn't open " + uri + ": " + SdlWindow.sdlError());
            }
        });
    }

    @Override
    public boolean showErrorDialog(String title, String message, boolean askToContinue) {
        if (!askToContinue) {
            boolean shown =
                    SDLMessageBox.SDL_ShowSimpleMessageBox(SDLMessageBox.SDL_MESSAGEBOX_ERROR, title, message, 0L);
            if (!shown) {
                log.error("Couldn't show the error '" + message + "': " + SdlWindow.sdlError(), null);
            }
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_MessageBoxButtonData.Buffer buttons = SDL_MessageBoxButtonData.calloc(2, stack);
            buttons.get(0)
                    .buttonID(CONTINUE)
                    .flags(SDLMessageBox.SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT)
                    .text(stack.UTF8("Yes"));
            buttons.get(1)
                    .buttonID(QUIT)
                    .flags(SDLMessageBox.SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT)
                    .text(stack.UTF8("No"));
            SDL_MessageBoxData box = SDL_MessageBoxData.calloc(stack)
                    .flags(SDLMessageBox.SDL_MESSAGEBOX_ERROR)
                    .title(stack.UTF8(title))
                    .message(stack.UTF8(message))
                    .buttons(buttons);
            IntBuffer chosen = stack.callocInt(1);
            if (!SDLMessageBox.SDL_ShowMessageBox(box, chosen)) {
                log.error("Couldn't show the error '" + message + "': " + SdlWindow.sdlError(), null);
                return false;
            }
            return chosen.get(0) == CONTINUE;
        }
    }
}
