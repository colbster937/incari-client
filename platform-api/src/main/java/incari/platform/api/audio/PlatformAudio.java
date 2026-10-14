package incari.platform.api.audio;

import org.jspecify.annotations.Nullable;

/**
 * Sound output. The game opens one {@link AudioDevice} at a time and plays positional sources
 * through it. On the desktop this is OpenAL; in the browser, Web Audio.
 */
public interface PlatformAudio {

    /** The output devices the player could choose, as of now. */
    AudioDeviceList devices();

    /**
     * Whether {@link #setDeviceChangeListener} will report device changes. When not, the game
     * polls {@link #devices} instead.
     */
    boolean reportsDeviceChanges();

    /**
     * Called, on an arbitrary thread, when devices are added or removed. Replaces any previous
     * listener; null removes it. Ignored when {@link #reportsDeviceChanges} is false.
     */
    void setDeviceChangeListener(@Nullable Runnable listener);

    /**
     * Opens an output device.
     *
     * @param deviceName a name from {@link #devices}, or null for the system default
     * @param spatialize whether to use head-related spatialization (HRTF) if the device has it
     * @throws AudioException if the device can't be opened or lacks what the game needs
     */
    AudioDevice open(@Nullable String deviceName, boolean spatialize);

    /**
     * The platform's own decoder for compressed sound files, or null if the game should decode
     * them itself and hand devices PCM (the desktop, where the game's decoder is the reference).
     */
    default @Nullable AudioDecoder decoder() {
        return null;
    }
}
