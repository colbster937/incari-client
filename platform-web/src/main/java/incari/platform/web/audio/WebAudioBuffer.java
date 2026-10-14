package incari.platform.web.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.PcmFormat;
import org.teavm.jso.JSObject;

/**
 * Samples a Web Audio buffer holds, playable by any number of sources. The page's garbage
 * collector frees it once no source plays it, so closing it only lets go of it.
 */
final class WebAudioBuffer implements AudioBuffer {

    private final JSObject buffer;
    private final PcmFormat format;
    private final int size;
    private final double duration;

    WebAudioBuffer(JSObject buffer, PcmFormat format, int size, int frames) {
        this.buffer = buffer;
        this.format = format;
        this.size = size;
        this.duration = (double) frames / format.sampleRate();
    }

    JSObject buffer() {
        return buffer;
    }

    /** Seconds, at normal speed. */
    double duration() {
        return duration;
    }

    boolean stereo() {
        return format.channels() == 2;
    }

    @Override
    public PcmFormat format() {
        return format;
    }

    @Override
    public int sizeInBytes() {
        return size;
    }

    @Override
    public void close() {}
}
