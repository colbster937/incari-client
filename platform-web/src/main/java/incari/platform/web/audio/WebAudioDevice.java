package incari.platform.web.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioListener;
import incari.platform.api.audio.AudioSource;
import incari.platform.api.audio.DecodedAudio;
import incari.platform.api.audio.PcmFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * An open output: a master gain on the page's audio context, and the sources playing into it.
 * Called from the game's sound thread (a coroutine on the page's thread).
 */
final class WebAudioDevice implements AudioDevice {

    /**
     * As many sources as OpenAL Soft gives the desktop by default (255 mono ones), so the game
     * splits them into the same pools and drops sounds at the same point.
     */
    static final int MAX_SOURCES = 255;

    private final JSObject context;
    private final JSObject bus;
    private final boolean hrtf;
    private final Spatializer spatializer = new Spatializer();
    private final List<WebAudioSource> sources = new ArrayList<>();
    private final AudioListener listener = new AudioListener() {
        @Override
        public void setPosition(double x, double y, double z) {
            // The game sets the orientation right after, and that updates the sources once.
            spatializer.setListenerPosition(x, y, z);
        }

        @Override
        public void setOrientation(double fx, double fy, double fz, double ux, double uy, double uz) {
            spatializer.setListenerOrientation(fx, fy, fz, ux, uy, uz);
            listenerMoved();
        }
    };
    private boolean closed;

    WebAudioDevice(JSObject context, boolean hrtf) {
        this.context = context;
        this.bus = AudioJs.createBus(context, hrtf);
        this.hrtf = hrtf;
    }

    @Override
    public String name() {
        return WebAudio.DEVICE_NAME;
    }

    @Override
    public boolean isDisconnected() {
        return closed || AudioJs.closed(context);
    }

    @Override
    public int maxSources() {
        return MAX_SOURCES;
    }

    @Override
    public AudioListener listener() {
        return listener;
    }

    @Override
    public @Nullable AudioSource createSource() {
        checkOpen();
        if (sources.size() >= MAX_SOURCES) {
            return null;
        }
        WebAudioSource source = new WebAudioSource(this, AudioJs.createVoice(bus));
        sources.add(source);
        return source;
    }

    @Override
    public AudioBuffer createBuffer(ByteBuffer samples, PcmFormat format) {
        checkOpen();
        int size = samples.remaining();
        byte[] bytes = new byte[size];
        samples.duplicate().get(bytes);
        int frames = size / format.frameSize();
        if (frames == 0) {
            throw new AudioException("An empty sound buffer");
        }
        JSObject buffer = AudioJs.pcmBuffer(
                context,
                Int8Array.copyFromJavaArray(bytes),
                format.sampleRate(),
                format.channels(),
                format.bitsPerSample());
        return new WebAudioBuffer(buffer, format, size, frames);
    }

    @Override
    public AudioBuffer createBuffer(DecodedAudio audio) {
        checkOpen();
        if (!(audio instanceof WebAudioDecoder.Decoded decoded)) {
            throw new AudioException("Not decoded by this browser: " + audio);
        }
        // Float samples, never PCM: the format records their rate and channels, at the depth the
        // desktop's decoder would have given them.
        PcmFormat format = new PcmFormat(decoded.sampleRate(), decoded.channels(), 16);
        return new WebAudioBuffer(decoded.buffer(), format, decoded.sizeInBytes(), decoded.frames());
    }

    @Override
    public boolean streamsOggVorbis() {
        return true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Closing a source removes it from the list.
        for (int i = sources.size() - 1; i >= 0; i--) {
            sources.get(i).close();
        }
        AudioJs.closeBus(bus);
    }

    JSObject context() {
        return context;
    }

    boolean hrtf() {
        return hrtf;
    }

    Spatializer spatializer() {
        return spatializer;
    }

    /** Context time, in seconds: what sources measure their progress against. */
    double now() {
        return AudioJs.time(context);
    }

    /** Hot path, every frame: re-pans every source against the moved listener, without allocating. */
    private void listenerMoved() {
        for (int i = 0, n = sources.size(); i < n; i++) {
            sources.get(i).listenerMoved();
        }
    }

    void forget(WebAudioSource source) {
        sources.remove(source);
    }

    private void checkOpen() {
        if (closed) {
            throw new AudioException("The audio device is closed");
        }
    }
}
