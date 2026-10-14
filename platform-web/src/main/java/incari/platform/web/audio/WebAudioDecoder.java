package incari.platform.web.audio;

import incari.platform.api.audio.AudioDecoder;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.DecodedAudio;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Decodes sound files with the browser's own decoder ({@code decodeAudioData}), which runs natively
 * on a thread of its own: a Chromebook decodes the game's Ogg files many times faster than the
 * game's Java decoder would on the page's only thread, and the page keeps drawing meanwhile.
 */
final class WebAudioDecoder implements AudioDecoder {

    private final WebAudio audio;

    WebAudioDecoder(WebAudio audio) {
        this.audio = audio;
    }

    @Override
    public DecodedAudio decodeOggVorbis(byte[] file) {
        // decodeAudioData takes its buffer away from the page, so it gets a copy of its own.
        Outcome outcome = decode(audio.context(), Int8Array.copyFromJavaArray(file));
        JSObject buffer = outcome.buffer();
        if (buffer == null) {
            throw new AudioException("The browser couldn't decode a sound: " + outcome.error());
        }
        int channels = AudioJs.bufferChannels(buffer);
        if (channels != 1 && channels != 2) {
            throw new AudioException("Sounds must be mono or stereo, not " + channels + " channels");
        }
        return new Decoded(buffer, AudioJs.bufferRate(buffer), channels, AudioJs.bufferFrames(buffer));
    }

    /**
     * The decoded buffer, or why decoding failed. A failure comes back as a value and is thrown by
     * the caller, not through {@code AsyncCallback.error}: TeaVM's WebAssembly GC runtime would also
     * rethrow it out of the JavaScript callback and crash the page, though the game catches it and
     * decodes the sound in Java (probe: {@code app-web/probes/FetchFailureProbe}).
     */
    private record Outcome(
            @Nullable JSObject buffer, @Nullable String error) {}

    /** Suspends the calling coroutine until the browser has decoded {@code bytes}: must not be the game's main thread. */
    @Async
    private static native Outcome decode(JSObject context, Int8Array bytes);

    private static void decode(JSObject context, Int8Array bytes, AsyncCallback<Outcome> callback) {
        AudioJs.decode(
                context,
                bytes,
                buffer -> callback.complete(new Outcome(buffer, null)),
                message -> callback.complete(new Outcome(null, message)));
    }

    /** A decoded sound: a Web Audio buffer of 32-bit float samples at the context's rate. */
    record Decoded(JSObject buffer, int sampleRate, int channels, int frames) implements DecodedAudio {

        @Override
        public int sizeInBytes() {
            return frames * channels * Float.BYTES;
        }
    }
}
