package incari.platform.desktop;

import static org.lwjgl.sdl.SDLEvents.*;

import incari.platform.api.input.KeyAction;
import incari.platform.api.window.PlatformEventListener;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_TextEditingEvent;

/** Drains SDL's event queue into a {@link PlatformEventListener}. */
final class SdlEventPump implements AutoCloseable {

    /**
     * The last event type counted as input: everything from key events up to the end of the pen
     * events (keys, text, mouse, joysticks, touch, drops, sensors, pens).
     */
    private static final int LAST_INPUT_EVENT = 0x13FF;

    /** Reused for every event: polling runs every frame and must not allocate. */
    private final SDL_Event event = SDL_Event.malloc();

    private final List<String> droppedFiles = new ArrayList<>();
    private @Nullable SdlWindow window;

    void setWindow(@Nullable SdlWindow window) {
        this.window = window;
    }

    void poll(PlatformEventListener listener) {
        while (SDLEvents.SDL_PollEvent(event)) {
            dispatch(listener);
        }
    }

    void flushInput() {
        SDLEvents.SDL_PumpEvents();
        SDLEvents.SDL_FlushEvents(SDL_EVENT_KEY_DOWN, LAST_INPUT_EVENT);
    }

    private void dispatch(PlatformEventListener listener) {
        switch (event.type()) {
            case SDL_EVENT_QUIT, SDL_EVENT_WINDOW_CLOSE_REQUESTED -> listener.closeRequested();
            case SDL_EVENT_TERMINATING -> listener.terminating();
            case SDL_EVENT_DISPLAY_ADDED ->
                listener.displayAdded(event.display().displayID());
            case SDL_EVENT_DISPLAY_REMOVED ->
                listener.displayRemoved(event.display().displayID());
            case SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED ->
                listener.displayModeChanged(event.display().displayID());
            case SDL_EVENT_WINDOW_MOVED ->
                listener.windowMoved(event.window().data1(), event.window().data2());
            case SDL_EVENT_WINDOW_RESIZED -> {
                SdlWindow current = window;
                if (current != null) {
                    // A resize can move the window between fullscreen states the grab depends on.
                    current.updateMouseGrab();
                }
                listener.windowResized(event.window().data1(), event.window().data2());
            }
            case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED ->
                listener.framebufferResized(
                        event.window().data1(), event.window().data2());
            case SDL_EVENT_WINDOW_MINIMIZED -> listener.minimizedChanged(true);
            case SDL_EVENT_WINDOW_MAXIMIZED, SDL_EVENT_WINDOW_RESTORED -> listener.minimizedChanged(false);
            case SDL_EVENT_WINDOW_MOUSE_ENTER, SDL_EVENT_WINDOW_MOUSE_LEAVE -> listener.cursorEnteredOrLeft();
            case SDL_EVENT_WINDOW_FOCUS_GAINED -> listener.focusChanged(true);
            case SDL_EVENT_WINDOW_FOCUS_LOST -> listener.focusChanged(false);
            case SDL_EVENT_WINDOW_DISPLAY_CHANGED -> listener.displayChanged();
            case SDL_EVENT_WINDOW_ENTER_FULLSCREEN -> listener.fullscreenChanged(true);
            case SDL_EVENT_WINDOW_LEAVE_FULLSCREEN -> listener.fullscreenChanged(false);
            case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> key(listener);
            case SDL_EVENT_TEXT_INPUT -> {
                String text = event.text().textString();
                if (text != null) {
                    listener.textInput(text);
                }
            }
            case SDL_EVENT_TEXT_EDITING -> {
                SDL_TextEditingEvent edit = event.edit();
                String text = edit.textString();
                listener.textEditing(text == null ? "" : text, edit.start(), edit.length());
            }
            case SDL_EVENT_KEYMAP_CHANGED -> listener.keymapChanged();
            case SDL_EVENT_MOUSE_MOTION -> {
                SDL_MouseMotionEvent motion = event.motion();
                listener.mouseMoved(motion.x(), motion.y(), motion.xrel(), motion.yrel());
            }
            case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP ->
                listener.mouseButton(
                        Byte.toUnsignedInt(event.button().button()),
                        event.button().down(),
                        modifiers());
            case SDL_EVENT_MOUSE_WHEEL -> {
                SDL_MouseWheelEvent wheel = event.wheel();
                listener.mouseWheel(wheel.x(), wheel.y(), modifiers());
            }
            case SDL_EVENT_DROP_BEGIN -> droppedFiles.clear();
            case SDL_EVENT_DROP_FILE -> {
                String path = event.drop().dataString();
                if (path != null) {
                    droppedFiles.add(path);
                }
            }
            case SDL_EVENT_DROP_COMPLETE -> {
                if (!droppedFiles.isEmpty()) {
                    listener.filesDropped(List.copyOf(droppedFiles));
                }
                droppedFiles.clear();
            }
            default -> {
                // Everything else (gamepads, sensors, pens, clipboard updates) isn't used yet.
            }
        }
    }

    private void key(PlatformEventListener listener) {
        SDL_KeyboardEvent key = event.key();
        KeyAction action = !key.down() ? KeyAction.RELEASE : key.repeat() ? KeyAction.REPEAT : KeyAction.PRESS;
        listener.key(key.scancode(), key.key(), key.mod() & 0xFFFF, action);
    }

    private static int modifiers() {
        return SDLKeyboard.SDL_GetModState() & 0xFFFF;
    }

    @Override
    public void close() {
        event.free();
    }
}
