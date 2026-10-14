package incari.app.desktop.smoke;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryUtil;

/**
 * Pushes synthetic events into SDL's queue, so the smoke test's input takes the same path a
 * player's does: SDL's queue, the desktop platform's event pump, the game's event handler.
 */
final class SdlInjector implements AutoCloseable {

    private final int windowId;
    /** Text events point at UTF-8 strings SDL reads later; they stay alive until close. */
    private final List<ByteBuffer> texts = new ArrayList<>();

    SdlInjector(int windowId) {
        this.windowId = windowId;
    }

    void focus() {
        push(event -> event.window(
                w -> w.type(SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED).windowID(windowId)));
    }

    /**
     * @param scancode the physical key (a HID usage id)
     * @param keyCode what it types in a US layout (SDL keycode)
     */
    void key(int scancode, int keyCode, boolean down) {
        push(event -> event.key(k -> k.type(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP)
                .windowID(windowId)
                .scancode(scancode)
                .key(keyCode)
                .down(down)));
    }

    void tap(int scancode, int keyCode) {
        key(scancode, keyCode, true);
        key(scancode, keyCode, false);
    }

    void text(String text) {
        ByteBuffer utf8 = MemoryUtil.memUTF8(text);
        texts.add(utf8);
        push(event -> event.text(
                t -> t.type(SDLEvents.SDL_EVENT_TEXT_INPUT).windowID(windowId).text(utf8)));
    }

    /** Relative mouse motion, as while looking around. */
    void look(float dx, float dy) {
        push(event -> event.motion(m -> m.type(SDLEvents.SDL_EVENT_MOUSE_MOTION)
                .windowID(windowId)
                .xrel(dx)
                .yrel(dy)));
    }

    /** Moves the pointer to a position in window coordinates, as over a menu. */
    void moveTo(float x, float y) {
        push(event -> event.motion(m ->
                m.type(SDLEvents.SDL_EVENT_MOUSE_MOTION).windowID(windowId).x(x).y(y)));
    }

    /** @param button 1 left, 3 right */
    void mouseButton(int button, boolean down) {
        push(event -> event.button(
                b -> b.type(down ? SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN : SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP)
                        .windowID(windowId)
                        .button((byte) button)
                        .down(down)
                        .clicks((byte) 1)));
    }

    private static void push(java.util.function.Consumer<SDL_Event> fill) {
        try (SDL_Event event = SDL_Event.calloc()) {
            fill.accept(event);
            if (!SDLEvents.SDL_PushEvent(event)) {
                throw new IllegalStateException("SDL refused an injected event");
            }
        }
    }

    @Override
    public void close() {
        texts.forEach(MemoryUtil::memFree);
        texts.clear();
    }
}
