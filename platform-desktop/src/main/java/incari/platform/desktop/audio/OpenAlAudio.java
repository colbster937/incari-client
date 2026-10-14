package incari.platform.desktop.audio;

import incari.platform.api.PlatformLog;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioDeviceList;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.PlatformAudio;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.openal.SOFTSystemEventProc;
import org.lwjgl.openal.SOFTSystemEvents;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link PlatformAudio} over OpenAL Soft, through LWJGL.
 *
 * <p>Nothing native is touched until the first call, so constructing this is free. LWJGL loads
 * OpenAL Soft when {@link ALC} is first used. {@link #close} undoes whatever was set up.
 */
public final class OpenAlAudio implements PlatformAudio, AutoCloseable {

    /** The device events that change what {@link #devices} returns. */
    private static final int[] DEVICE_EVENTS = {
        SOFTSystemEvents.ALC_EVENT_TYPE_DEFAULT_DEVICE_CHANGED_SOFT,
        SOFTSystemEvents.ALC_EVENT_TYPE_DEVICE_ADDED_SOFT,
        SOFTSystemEvents.ALC_EVENT_TYPE_DEVICE_REMOVED_SOFT,
    };

    private final PlatformLog log;
    private final Object lock = new Object();

    private @Nullable Boolean eventsSupported;

    /**
     * Kept for as long as it's installed: OpenAL holds only its native address, so letting it be
     * collected would leave OpenAL calling freed memory.
     */
    private @Nullable SOFTSystemEventProc eventCallback;

    private volatile @Nullable Runnable deviceChangeListener;

    /** The device opened last, closed with this if the game didn't close it first. */
    private @Nullable OpenAlDevice lastOpened;

    private boolean closed;

    public OpenAlAudio(PlatformLog log) {
        this.log = log;
    }

    @Override
    public AudioDeviceList devices() {
        loadOpenAl();
        // Without ALC_ENUMERATE_ALL_EXT the only names on offer are the coarse ALC 1.0 ones,
        // which don't match what an opened device reports; better to list nothing.
        if (!ALC10.alcIsExtensionPresent(MemoryUtil.NULL, "ALC_ENUMERATE_ALL_EXT")) {
            return AudioDeviceList.EMPTY;
        }
        List<String> all = ALUtil.getStringList(MemoryUtil.NULL, ALC11.ALC_ALL_DEVICES_SPECIFIER);
        String defaultDevice = ALC10.alcGetString(MemoryUtil.NULL, ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
        return new AudioDeviceList(defaultDevice, all == null ? List.of() : all);
    }

    @Override
    public boolean reportsDeviceChanges() {
        synchronized (lock) {
            Boolean supported = eventsSupported;
            if (supported == null) {
                supported = querySystemEvents();
                eventsSupported = supported;
            }
            return supported;
        }
    }

    @Override
    public void setDeviceChangeListener(@Nullable Runnable listener) {
        deviceChangeListener = listener;
        if (listener == null || !reportsDeviceChanges()) {
            return;
        }
        synchronized (lock) {
            if (eventCallback != null || closed) {
                return;
            }
            // Installed once and left installed: the callback reads the current listener, so
            // swapping listeners needs no native calls.
            SOFTSystemEventProc callback = SOFTSystemEventProc.create(this::onSystemEvent);
            if (!SOFTSystemEvents.alcEventControlSOFT(DEVICE_EVENTS, true)) {
                callback.free();
                log.warn("OpenAL refused to report device changes: "
                        + OpenAlSupport.alcErrorName(ALC10.alcGetError(MemoryUtil.NULL)));
                return;
            }
            SOFTSystemEvents.alcEventCallbackSOFT(callback, MemoryUtil.NULL);
            eventCallback = callback;
        }
    }

    @Override
    public AudioDevice open(@Nullable String deviceName, boolean spatialize) {
        synchronized (lock) {
            if (closed) {
                throw new AudioException("The audio system was shut down");
            }
        }
        loadOpenAl();
        OpenAlDevice device = OpenAlDevice.open(log, deviceName, spatialize);
        synchronized (lock) {
            lastOpened = device;
        }
        return device;
    }

    /**
     * Stops device-change reports and closes a device the game left open. Safe to call more than
     * once, and on an instance that never touched OpenAL (then it loads nothing).
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            deviceChangeListener = null;
            SOFTSystemEventProc callback = eventCallback;
            if (callback != null) {
                SOFTSystemEvents.alcEventControlSOFT(DEVICE_EVENTS, false);
                SOFTSystemEvents.alcEventCallbackSOFT(null, MemoryUtil.NULL);
                callback.free();
                eventCallback = null;
            }
            OpenAlDevice device = lastOpened;
            if (device != null) {
                device.close();
                lastOpened = null;
            }
        }
    }

    /** Whether every device event the game needs can be reported for playback devices. */
    private boolean querySystemEvents() {
        loadOpenAl();
        if (!ALC10.alcIsExtensionPresent(MemoryUtil.NULL, "ALC_SOFT_system_events")) {
            return false;
        }
        for (int event : DEVICE_EVENTS) {
            int support = SOFTSystemEvents.alcEventIsSupportedSOFT(event, SOFTSystemEvents.ALC_PLAYBACK_DEVICE_SOFT);
            if (support != SOFTSystemEvents.ALC_EVENT_SUPPORTED_SOFT) {
                if (support == 0) {
                    log.warn("Couldn't ask OpenAL about device event 0x" + Integer.toHexString(event) + ": "
                            + OpenAlSupport.alcErrorName(ALC10.alcGetError(MemoryUtil.NULL)));
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Runs on OpenAL's own event thread. Must not call back into OpenAL or block: it only logs and
     * tells the listener, which in the game just raises a flag.
     */
    @SuppressWarnings("UnusedVariable") // The callback's full native signature.
    private void onSystemEvent(
            int eventType, int deviceType, long device, int messageLength, long message, long userParam) {
        if (deviceType != SOFTSystemEvents.ALC_PLAYBACK_DEVICE_SOFT) {
            return; // Microphones coming and going don't change what the game can play to.
        }
        log.debug("Audio device event 0x" + Integer.toHexString(eventType) + ": "
                + MemoryUtil.memUTF8(message, messageLength));
        Runnable listener = deviceChangeListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Loads OpenAL Soft if nothing has yet. LWJGL does so in {@link ALC}'s static initializer, so
     * touching the class is enough.
     */
    private static void loadOpenAl() {
        try {
            ALC.getFunctionProvider();
        } catch (RuntimeException | LinkageError e) {
            throw new AudioException("OpenAL couldn't be loaded", e);
        }
    }
}
