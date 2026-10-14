package incari.platform.desktop;

import incari.platform.api.PlatformLog;
import org.lwjgl.sdl.SDLLog;
import org.lwjgl.sdl.SDL_LogOutputFunction;
import org.lwjgl.system.MemoryUtil;

/** Sends SDL's own log messages to the platform log, tagged with SDL's category. */
final class SdlLogBridge {

    private final PlatformLog log;
    /** Held so the native callback isn't freed while SDL can still call it. */
    private final SDL_LogOutputFunction callback;

    SdlLogBridge(PlatformLog log) {
        this.log = log;
        this.callback = SDL_LogOutputFunction.create(this::onMessage);
    }

    void install(boolean debug) {
        SDLLog.SDL_SetLogOutputFunction(callback, 0L);
        SDLLog.SDL_SetLogPriorities(debug ? SDLLog.SDL_LOG_PRIORITY_DEBUG : SDLLog.SDL_LOG_PRIORITY_INFO);
    }

    private void onMessage(long userData, int category, int priority, long message) {
        String text = message == 0L ? "" : MemoryUtil.memUTF8(message);
        String line = "SDL [" + categoryName(category) + "]: " + text;
        if (priority <= SDLLog.SDL_LOG_PRIORITY_DEBUG) {
            log.debug(line);
        } else if (priority == SDLLog.SDL_LOG_PRIORITY_INFO) {
            log.info(line);
        } else if (priority == SDLLog.SDL_LOG_PRIORITY_WARN) {
            log.warn(line);
        } else {
            log.error(line, null);
        }
    }

    /** SDL's built-in log categories (SDL_LogCategory), by their documented order. */
    private static String categoryName(int category) {
        String[] names = {"APPLICATION", "ERROR", "ASSERT", "SYSTEM", "AUDIO", "VIDEO", "RENDER", "INPUT", "TEST", "GPU"
        };
        return category >= 0 && category < names.length ? names[category] : "category " + category;
    }
}
