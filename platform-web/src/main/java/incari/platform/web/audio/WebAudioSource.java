package incari.platform.web.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioSource;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * One source, with OpenAL's semantics over Web Audio's nodes: a static buffer (paused by
 * stopping its node and remembering where), a queue of buffers scheduled back to back, or a
 * compressed stream a media element plays. Its gains come from the device's {@link Spatializer}
 * and are pushed to the page only when they change.
 */
final class WebAudioSource implements AudioSource {

    private enum Content {
        NONE,
        BUFFER,
        QUEUE,
        STREAM
    }

    private final WebAudioDevice device;
    private final JSObject voice;
    private final Spatializer.Attenuation attenuation = new Spatializer.Attenuation();
    private final PlaybackClock clock = new PlaybackClock();
    private final StreamTimeline timeline = new StreamTimeline();
    private final List<WebAudioBuffer> queue = new ArrayList<>();

    private Content content = Content.NONE;
    private @Nullable WebAudioBuffer buffer;
    private boolean stereo;
    private State state = State.INITIAL;
    /** After {@link #stop}, every queued buffer counts as finished, as in OpenAL. */
    private boolean queueStopped;

    private boolean open = true;

    private double x;
    private double y;
    private double z;
    private boolean relative;
    private float gain = 1;
    private float pitch = 1;
    private boolean looping;

    // What the page was last told, so unchanged gains aren't sent again.
    private double sentLeft = Double.NaN;
    private double sentRight = Double.NaN;

    WebAudioSource(WebAudioDevice device, JSObject voice) {
        this.device = device;
        this.voice = voice;
    }

    @Override
    public void play() {
        if (!open) {
            return;
        }
        double now = device.now();
        if (state == State.PLAYING) {
            // OpenAL plays a playing source again from the start.
            halt();
            rewind();
        } else if (state == State.STOPPED || state == State.INITIAL) {
            rewind();
        }
        updateGains(true);
        switch (content) {
            case NONE -> {
                // OpenAL: a source with nothing to play stops at once.
                state = State.STOPPED;
                return;
            }
            case BUFFER -> {
                WebAudioBuffer current = buffer;
                if (current == null || (state != State.PAUSED && !AudioJs.startsSoon(device.context()))) {
                    // Before the page's first click or key press the context may not run for
                    // minutes: short sounds are dropped rather than all heard at once then.
                    state = State.STOPPED;
                    return;
                }
                double position = clock.position(now);
                double offset = looping ? PlaybackClock.wrap(position, current.duration()) : position;
                if (offset >= current.duration()) {
                    state = State.STOPPED;
                    return;
                }
                AudioJs.startBuffer(voice, current.buffer(), 0, offset, pitch, looping);
            }
            case QUEUE -> scheduleQueue(now);
            case STREAM -> AudioJs.playMedia(voice);
        }
        clock.start(now);
        state = State.PLAYING;
    }

    @Override
    public void pause() {
        if (!open || state != State.PLAYING) {
            return;
        }
        clock.pause(device.now());
        halt();
        state = State.PAUSED;
    }

    @Override
    public void stop() {
        if (!open) {
            return;
        }
        halt();
        if (content == Content.STREAM) {
            AudioJs.pauseMedia(voice, true);
        }
        clock.pause(device.now());
        // As in OpenAL Soft, a source that never played stays initial.
        if (state != State.INITIAL) {
            state = State.STOPPED;
            queueStopped = content == Content.QUEUE;
        }
    }

    @Override
    public State state() {
        if (open && state == State.PLAYING && finished()) {
            clock.pause(device.now());
            state = State.STOPPED;
        }
        return open ? state : State.STOPPED;
    }

    /** Whether what's playing has run out: the last buffer ended, or the queue or the stream did. */
    private boolean finished() {
        return switch (content) {
            case NONE -> true;
            case BUFFER -> !looping && AudioJs.buffersEnded(voice);
            case QUEUE -> clock.position(device.now()) >= timeline.end();
            case STREAM -> AudioJs.mediaEnded(voice);
        };
    }

    @Override
    public void setPosition(double newX, double newY, double newZ) {
        x = newX;
        y = newY;
        z = newZ;
        updateGains(false);
    }

    @Override
    public void setPitch(float newPitch) {
        if (!(newPitch > 0)) {
            return; // OpenAL rejects a pitch that isn't positive and keeps the old one.
        }
        double now = device.now();
        clock.setRate(now, newPitch);
        pitch = newPitch;
        if (content == Content.QUEUE) {
            if (state == State.PLAYING) {
                // Buffers already scheduled at the old speed start at the wrong times: schedule again.
                AudioJs.stopBuffers(voice);
                scheduleQueue(now);
            }
        } else {
            AudioJs.setRate(voice, newPitch);
        }
    }

    @Override
    public void setLooping(boolean newLooping) {
        looping = newLooping;
        AudioJs.setLoop(voice, newLooping);
    }

    @Override
    public void setGain(float newGain) {
        // OpenAL clamps a source's gain to its maximum of 1.
        gain = Math.clamp(newGain, 0.0F, 1.0F);
        updateGains(false);
    }

    @Override
    public void setRelative(boolean newRelative) {
        relative = newRelative;
        updateGains(false);
    }

    @Override
    public void disableAttenuation() {
        attenuation.none();
        updateGains(false);
    }

    @Override
    public void setLinearAttenuation(float maxDistance) {
        attenuation.linear(maxDistance);
        updateGains(false);
    }

    @Override
    public void setBuffer(AudioBuffer newBuffer) {
        if (state == State.PLAYING || state == State.PAUSED) {
            throw new AudioException("Can't change the buffer of a playing source");
        }
        WebAudioBuffer ours = ours(newBuffer);
        clearQueue();
        buffer = ours;
        content = Content.BUFFER;
        setStereo(ours.stereo());
    }

    @Override
    public void queue(AudioBuffer newBuffer) {
        WebAudioBuffer ours = ours(newBuffer);
        if (content == Content.BUFFER || content == Content.STREAM) {
            throw new AudioException("Can't queue buffers on a source playing something else");
        }
        if (content == Content.NONE) {
            content = Content.QUEUE;
            setStereo(ours.stereo());
        }
        double now = device.now();
        boolean ranOut = state == State.PLAYING && clock.position(now) >= timeline.end();
        queue.add(ours);
        timeline.add(ours.duration());
        if (state != State.PLAYING) {
            return;
        }
        if (ranOut) {
            // The queue ran dry before this arrived: OpenAL has stopped the source by now.
            clock.pause(now);
            state = State.STOPPED;
            return;
        }
        int index = queue.size() - 1;
        double start = timeline.startOf(index);
        double when = now + (start - clock.position(now)) / pitch;
        AudioJs.startBuffer(voice, ours.buffer(), when, 0, pitch, false);
    }

    @Override
    public int releaseProcessedBuffers() {
        if (!open || content != Content.QUEUE || queue.isEmpty()) {
            return 0;
        }
        int finished = queueStopped ? queue.size() : timeline.finishedAt(clock.position(device.now()));
        for (int i = 0; i < finished; i++) {
            queue.get(i).close();
        }
        queue.subList(0, finished).clear();
        timeline.release(finished);
        return finished;
    }

    @Override
    public void streamOggVorbis(byte[] file, boolean loop) {
        if (content != Content.NONE) {
            throw new AudioException("Can't stream on a source playing something else");
        }
        OggVorbisInfo info = OggVorbisInfo.read(file);
        if (info == null) {
            throw new AudioException("Not an Ogg Vorbis file");
        }
        content = Content.STREAM;
        looping = loop;
        setStereo(info.channels() != 1);
        AudioJs.attachMedia(voice, Int8Array.copyFromJavaArray(file), loop, pitch);
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        AudioJs.closeVoice(voice);
        clearQueue();
        buffer = null;
        device.forget(this);
    }

    /** The listener moved: hot path, every frame for every source, so nothing here allocates. */
    void listenerMoved() {
        if (!relative) {
            updateGains(false);
        }
    }

    private void setStereo(boolean newStereo) {
        stereo = newStereo;
        AudioJs.route(voice, newStereo);
        sentLeft = Double.NaN;
        updateGains(true);
    }

    /** Works out the ear gains and sends them if they changed; {@code immediate} skips smoothing. */
    private void updateGains(boolean immediate) {
        if (!open) {
            return;
        }
        Spatializer spatializer = device.spatializer();
        spatializer.compute(x, y, z, relative, attenuation, gain, stereo);
        double left;
        double right;
        if (device.hrtf() && !stereo) {
            // The HRTF panner does the panning; the ears get the attenuated gain.
            left = spatializer.level();
            right = left;
            AudioJs.setPannerPosition(voice, spatializer.localX(), spatializer.localY(), spatializer.localZ());
        } else {
            left = spatializer.left();
            right = spatializer.right();
        }
        if (left == sentLeft && right == sentRight) {
            return;
        }
        sentLeft = left;
        sentRight = right;
        AudioJs.setGains(voice, left, right, immediate || state != State.PLAYING);
    }

    /** Starts the queued buffers from the clock's position: the current one part way, the rest after it. */
    private void scheduleQueue(double now) {
        double position = clock.position(now);
        for (int i = timeline.indexAt(position); i < queue.size(); i++) {
            double start = timeline.startOf(i);
            if (start <= position) {
                AudioJs.startBuffer(voice, queue.get(i).buffer(), 0, position - start, pitch, false);
            } else {
                AudioJs.startBuffer(voice, queue.get(i).buffer(), now + (start - position) / pitch, 0, pitch, false);
            }
        }
    }

    /** Silences whatever is playing, keeping the stream element and the queue. */
    private void halt() {
        AudioJs.stopBuffers(voice);
        if (content == Content.STREAM) {
            AudioJs.pauseMedia(voice, false);
        }
    }

    /** Back to the start of what's attached: a queue restarts from its first unreleased buffer. */
    private void rewind() {
        clock.reset();
        if (content == Content.QUEUE) {
            clock.seek(device.now(), timeline.startOf(0));
            queueStopped = false;
        }
    }

    private void clearQueue() {
        for (WebAudioBuffer queued : queue) {
            queued.close();
        }
        queue.clear();
        timeline.clear();
        queueStopped = false;
    }

    private static WebAudioBuffer ours(AudioBuffer buffer) {
        if (buffer instanceof WebAudioBuffer web) {
            return web;
        }
        throw new AudioException("Not a Web Audio buffer: " + buffer);
    }
}
