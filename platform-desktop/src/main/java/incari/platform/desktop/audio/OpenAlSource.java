package incari.platform.desktop.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioSource;
import java.util.ArrayDeque;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTLinearDistance;

/**
 * An OpenAL source. Confined to the game's sound thread, like everything the game does with a
 * source.
 */
final class OpenAlSource implements AudioSource {

    private final OpenAlDevice owner;
    private final int id;

    /**
     * The buffers queued on the source, oldest first. OpenAL finishes and unqueues them in queue
     * order, so the head is always the next one {@link #releaseProcessedBuffers} gets back.
     */
    private final ArrayDeque<OpenAlBuffer> queued = new ArrayDeque<>();

    private boolean open = true;

    OpenAlSource(OpenAlDevice owner, int id) {
        this.owner = owner;
        this.id = id;
    }

    @Override
    public void play() {
        if (open) {
            AL10.alSourcePlay(id);
        }
    }

    @Override
    public void pause() {
        if (open) {
            AL10.alSourcePause(id);
        }
    }

    @Override
    public void stop() {
        if (open) {
            AL10.alSourceStop(id);
            owner.logAlError("Couldn't stop an audio source");
        }
    }

    @Override
    public State state() {
        if (!open) {
            return State.STOPPED;
        }
        return switch (AL10.alGetSourcei(id, AL10.AL_SOURCE_STATE)) {
            case AL10.AL_INITIAL -> State.INITIAL;
            case AL10.AL_PLAYING -> State.PLAYING;
            case AL10.AL_PAUSED -> State.PAUSED;
            default -> State.STOPPED;
        };
    }

    @Override
    public void setPosition(double x, double y, double z) {
        AL10.alSource3f(id, AL10.AL_POSITION, (float) x, (float) y, (float) z);
    }

    @Override
    public void setPitch(float pitch) {
        AL10.alSourcef(id, AL10.AL_PITCH, pitch);
    }

    @Override
    public void setLooping(boolean looping) {
        AL10.alSourcei(id, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
    }

    @Override
    public void setGain(float gain) {
        AL10.alSourcef(id, AL10.AL_GAIN, gain);
    }

    @Override
    public void setRelative(boolean relative) {
        AL10.alSourcei(id, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
    }

    @Override
    public void disableAttenuation() {
        // Per-source model (AL_EXT_source_distance_model, enabled when the device opened).
        AL10.alSourcei(id, AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
    }

    @Override
    public void setLinearAttenuation(float maxDistance) {
        // Unclamped linear model with a reference distance of 0 and a rolloff of 1: gain falls in
        // a straight line from 1 at the source to 0 at maxDistance, and stays 0 beyond it.
        AL10.alSourcei(id, AL10.AL_DISTANCE_MODEL, EXTLinearDistance.AL_LINEAR_DISTANCE);
        AL10.alSourcef(id, AL10.AL_MAX_DISTANCE, maxDistance);
        AL10.alSourcef(id, AL10.AL_ROLLOFF_FACTOR, 1.0F);
        AL10.alSourcef(id, AL10.AL_REFERENCE_DISTANCE, 0.0F);
    }

    @Override
    public void setBuffer(AudioBuffer buffer) {
        AL10.alSourcei(id, AL10.AL_BUFFER, ours(buffer).id());
        owner.logAlError("Couldn't attach an audio buffer");
    }

    @Override
    public void queue(AudioBuffer buffer) {
        OpenAlBuffer alBuffer = ours(buffer);
        AL10.alSourceQueueBuffers(id, alBuffer.id());
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            // Not queued, so nobody else will close it; the stream just skips this chunk.
            alBuffer.close();
            owner.log().error("Couldn't queue an audio buffer: " + OpenAlSupport.alErrorName(error), null);
            return;
        }
        queued.addLast(alBuffer);
    }

    @Override
    public int releaseProcessedBuffers() {
        if (!open || queued.isEmpty()) {
            return 0;
        }
        int processed = AL10.alGetSourcei(id, AL10.AL_BUFFERS_PROCESSED);
        if (processed <= 0) {
            return 0;
        }
        int[] ids = new int[processed];
        AL10.alSourceUnqueueBuffers(id, ids);
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            // Nothing was unqueued. Report none, so the caller doesn't queue more on top.
            owner.log().error("Couldn't unqueue audio buffers: " + OpenAlSupport.alErrorName(error), null);
            return 0;
        }
        for (int i = 0; i < processed && !queued.isEmpty(); i++) {
            queued.removeFirst().close();
        }
        return processed;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        owner.forget(this);
        if (owner.isReleased()) {
            return;
        }
        AL10.alSourceStop(id);
        // Detaching every buffer (static or queued) is allowed once stopped, and lets them be deleted.
        AL10.alSourcei(id, AL10.AL_BUFFER, 0);
        AL10.alDeleteSources(id);
        owner.logAlError("Couldn't delete an audio source");
        while (!queued.isEmpty()) {
            queued.removeFirst().close();
        }
    }

    private OpenAlBuffer ours(AudioBuffer buffer) {
        if (buffer instanceof OpenAlBuffer alBuffer) {
            return alBuffer;
        }
        throw new AudioException("Not an OpenAL buffer: " + buffer);
    }
}
