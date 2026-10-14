package incari.platform.web.audio;

import incari.platform.api.audio.AudioDecoder;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioDeviceList;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.PlatformAudio;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;

/**
 * Sound in the browser, over Web Audio (ADR 0013).
 *
 * <p>The page has one audio context for its whole life, made when the game first opens a device
 * or decodes a sound. Devices come and go on it (the game reopens its device when resources
 * reload), and decoded sounds outlive them. Browsers start a context suspended until the page gets
 * a click or a key press, so opening it also shows the unlock prompt ({@link AudioJs#installUnlock}).
 *
 * <p>There's one output, the browser's: a page can pick another only with {@code setSinkId},
 * which isn't in every browser and asks for a permission; that can come later.
 */
public final class WebAudio implements PlatformAudio {

    /** The one device's name, as the game's sound device option lists it. */
    static final String DEVICE_NAME = "Browser audio output";

    private final WebAudioDecoder decoder = new WebAudioDecoder(this);
    private @Nullable JSObject context;

    @Override
    public AudioDeviceList devices() {
        return new AudioDeviceList(DEVICE_NAME, List.of(DEVICE_NAME));
    }

    @Override
    public boolean reportsDeviceChanges() {
        return false;
    }

    @Override
    public void setDeviceChangeListener(@Nullable Runnable listener) {}

    @Override
    public AudioDevice open(@Nullable String deviceName, boolean spatialize) {
        if (deviceName != null && !deviceName.equals(DEVICE_NAME)) {
            throw new AudioException("No audio device named '" + deviceName + "' in the browser");
        }
        return new WebAudioDevice(context(), spatialize);
    }

    @Override
    public AudioDecoder decoder() {
        return decoder;
    }

    /**
     * The page's audio context, made on first use, with the unlock prompt.
     *
     * @throws AudioException if the browser has no Web Audio
     */
    JSObject context() {
        JSObject current = context;
        if (current == null) {
            current = AudioJs.createContext();
            if (current == null) {
                throw new AudioException("This browser has no Web Audio");
            }
            AudioJs.defineHelpers();
            AudioJs.installUnlock(current);
            context = current;
        }
        return current;
    }
}
