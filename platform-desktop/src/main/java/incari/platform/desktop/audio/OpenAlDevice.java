package incari.platform.desktop.audio;

import incari.platform.api.PlatformLog;
import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioListener;
import incari.platform.api.audio.AudioSource;
import incari.platform.api.audio.PcmFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTDisconnect;
import org.lwjgl.openal.EXTSourceDistanceModel;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryUtil;

/**
 * An OpenAL device with its one context, made current for the whole process. The game keeps a
 * single device open at a time, so a process-wide context needs no switching.
 */
final class OpenAlDevice implements AudioDevice {

    private final PlatformLog log;
    private final long device;
    private final long context;
    private final String name;
    private final boolean reportsDisconnect;
    private final int maxSources;
    private final OpenAlListener listener = new OpenAlListener();

    // Buffers are closed from whichever thread drops the game's sound cache, so these are
    // concurrent sets rather than confined to the sound thread.
    private final Set<OpenAlSource> sources = ConcurrentHashMap.newKeySet();
    private final Set<OpenAlBuffer> buffers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    /** Set once the native device is gone, after {@link #closed}: nothing may call OpenAL for it then. */
    private volatile boolean released;

    private OpenAlDevice(
            PlatformLog log, long device, long context, String name, boolean reportsDisconnect, int maxSources) {
        this.log = log;
        this.device = device;
        this.context = context;
        this.name = name;
        this.reportsDisconnect = reportsDisconnect;
        this.maxSources = maxSources;
    }

    /**
     * Opens {@code deviceName} (null for the default), creates its context and checks it can do
     * what the game needs: ALC 1.1, per-source distance models and the linear distance model.
     */
    static OpenAlDevice open(PlatformLog log, @Nullable String deviceName, boolean spatialize) {
        String shownName = deviceName == null ? "the default audio device" : "audio device '" + deviceName + "'";
        long device = ALC10.alcOpenDevice(deviceName);
        if (device == MemoryUtil.NULL) {
            throw new AudioException("Couldn't open " + shownName + ": "
                    + OpenAlSupport.alcErrorName(ALC10.alcGetError(MemoryUtil.NULL)));
        }
        long context = MemoryUtil.NULL;
        try {
            ALCCapabilities alc = ALC.createCapabilities(device);
            if (!alc.OpenALC11) {
                throw new AudioException(shownName + " doesn't support OpenAL 1.1");
            }

            int hrtfCount = alc.ALC_SOFT_HRTF ? ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT) : 0;
            ALC10.alcGetError(device); // A device that can't count its HRTFs has none; don't fail on it.
            int[] attributes = OpenAlSupport.contextAttributes(hrtfCount, spatialize, alc.ALC_SOFT_output_limiter);
            context = ALC10.alcCreateContext(device, attributes);
            int error = ALC10.alcGetError(device);
            if (context == MemoryUtil.NULL || error != ALC10.ALC_NO_ERROR) {
                throw new AudioException(
                        "Couldn't create an OpenAL context on " + shownName + ": " + OpenAlSupport.alcErrorName(error));
            }
            if (!ALC10.alcMakeContextCurrent(context)) {
                throw new AudioException("Couldn't make the OpenAL context current on " + shownName);
            }

            ALCapabilities al = AL.createCapabilities(alc);
            if (!al.AL_EXT_source_distance_model) {
                throw new AudioException(shownName + " doesn't support AL_EXT_source_distance_model");
            }
            if (!al.AL_EXT_LINEAR_DISTANCE) {
                throw new AudioException(shownName + " doesn't support AL_EXT_LINEAR_DISTANCE");
            }
            // Sources carry their own distance model: some sounds fade with distance, music doesn't.
            AL10.alEnable(EXTSourceDistanceModel.AL_SOURCE_DISTANCE_MODEL);
            int alError = AL10.alGetError();
            if (alError != AL10.AL_NO_ERROR) {
                throw new AudioException("Couldn't enable per-source distance models on " + shownName + ": "
                        + OpenAlSupport.alErrorName(alError));
            }

            String name = queryName(device);
            int maxSources = OpenAlSupport.monoSourceCount(queryAttributes(device));
            boolean reportsDisconnect = ALC10.alcIsExtensionPresent(device, "ALC_EXT_disconnect");
            log.info("Opened audio device " + name + " (" + maxSources + " sources, HRTF "
                    + hrtfState(alc, device, hrtfCount) + ")");
            return new OpenAlDevice(log, device, context, name, reportsDisconnect, maxSources);
        } catch (RuntimeException e) {
            if (context != MemoryUtil.NULL) {
                ALC10.alcMakeContextCurrent(MemoryUtil.NULL);
                ALC10.alcDestroyContext(context);
            }
            ALC10.alcCloseDevice(device);
            throw e instanceof AudioException audio ? audio : new AudioException("Couldn't set up " + shownName, e);
        }
    }

    /** The full name, which is what device enumeration lists, falling back to the short one. */
    private static String queryName(long device) {
        String name = ALC10.alcGetString(device, ALC11.ALC_ALL_DEVICES_SPECIFIER);
        if (name == null) {
            name = ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER);
        }
        return name != null ? name : "Unknown (0x" + Long.toHexString(device) + ")";
    }

    /** The attributes the context was actually created with, as key/value pairs. */
    private static int[] queryAttributes(long device) {
        int size = ALC10.alcGetInteger(device, ALC10.ALC_ATTRIBUTES_SIZE);
        if (ALC10.alcGetError(device) != ALC10.ALC_NO_ERROR || size <= 0) {
            return new int[0];
        }
        int[] attributes = new int[size];
        ALC10.alcGetIntegerv(device, ALC10.ALC_ALL_ATTRIBUTES, attributes);
        return ALC10.alcGetError(device) == ALC10.ALC_NO_ERROR ? attributes : new int[0];
    }

    private static String hrtfState(ALCCapabilities alc, long device, int hrtfCount) {
        if (!alc.ALC_SOFT_HRTF || hrtfCount <= 0) {
            return "unavailable";
        }
        int status = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
        return switch (status) {
            case SOFTHRTF.ALC_HRTF_ENABLED_SOFT -> "on";
            case SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT -> "on (headphones detected)";
            case SOFTHRTF.ALC_HRTF_REQUIRED_SOFT -> "on (required by the device)";
            case SOFTHRTF.ALC_HRTF_DENIED_SOFT -> "off (denied by configuration)";
            case SOFTHRTF.ALC_HRTF_UNSUPPORTED_FORMAT_SOFT -> "off (unsupported output format)";
            default -> "off";
        };
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isDisconnected() {
        if (closed) {
            return true;
        }
        return reportsDisconnect && ALC10.alcGetInteger(device, EXTDisconnect.ALC_CONNECTED) == ALC10.ALC_FALSE;
    }

    @Override
    public int maxSources() {
        return maxSources;
    }

    @Override
    public AudioListener listener() {
        return listener;
    }

    @Override
    public @Nullable AudioSource createSource() {
        checkOpen();
        if (sources.size() >= maxSources) {
            return null;
        }
        AL10.alGetError(); // Clear anything stale so the check below is about this call.
        int id = AL10.alGenSources();
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            log.error("Couldn't create an audio source: " + OpenAlSupport.alErrorName(error), null);
            return null;
        }
        OpenAlSource source = new OpenAlSource(this, id);
        sources.add(source);
        return source;
    }

    @Override
    public AudioBuffer createBuffer(ByteBuffer samples, PcmFormat format) {
        checkOpen();
        int alFormat = OpenAlSupport.alFormat(format);
        int size = samples.remaining();
        AL10.alGetError();
        int id = AL10.alGenBuffers();
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new AudioException("Couldn't create an audio buffer: " + OpenAlSupport.alErrorName(error));
        }
        // OpenAL copies the samples during the call, so a heap buffer only needs a short-lived
        // native copy. The game's decoder already produces direct buffers.
        if (samples.isDirect()) {
            AL10.alBufferData(id, alFormat, samples, format.sampleRate());
        } else {
            ByteBuffer copy = MemoryUtil.memAlloc(size);
            try {
                copy.put(samples.duplicate()).flip();
                AL10.alBufferData(id, alFormat, copy, format.sampleRate());
            } finally {
                MemoryUtil.memFree(copy);
            }
        }
        error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            AL10.alDeleteBuffers(id);
            throw new AudioException(
                    "Couldn't upload " + size + " bytes of " + format + ": " + OpenAlSupport.alErrorName(error));
        }
        OpenAlBuffer buffer = new OpenAlBuffer(this, id, format, size);
        buffers.add(buffer);
        return buffer;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Sources first: a buffer can't be deleted while a source still holds it.
        for (OpenAlSource source : new ArrayList<>(sources)) {
            source.close();
        }
        for (OpenAlBuffer buffer : new ArrayList<>(buffers)) {
            buffer.close();
        }
        if (ALC10.alcGetCurrentContext() == context) {
            ALC10.alcMakeContextCurrent(MemoryUtil.NULL);
        }
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
        released = true;
    }

    boolean isReleased() {
        return released;
    }

    PlatformLog log() {
        return log;
    }

    void forget(OpenAlSource source) {
        sources.remove(source);
    }

    void forget(OpenAlBuffer buffer) {
        buffers.remove(buffer);
    }

    /**
     * Logs an AL error left by the calls just made, if any. For calls whose failure the game
     * can't act on anyway (it never checks a stop or a delete).
     */
    void logAlError(String what) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            log.error(what + ": " + OpenAlSupport.alErrorName(error), null);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new AudioException("Audio device " + name + " is closed");
        }
    }
}
