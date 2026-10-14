package incari.platform.desktop.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.PcmFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.openal.AL10;

/** An OpenAL buffer name and what was uploaded into it. */
final class OpenAlBuffer implements AudioBuffer {

    private final OpenAlDevice owner;
    private final int id;
    private final PcmFormat format;
    private final int size;
    private final AtomicBoolean open = new AtomicBoolean(true);

    OpenAlBuffer(OpenAlDevice owner, int id, PcmFormat format, int size) {
        this.owner = owner;
        this.id = id;
        this.format = format;
        this.size = size;
    }

    int id() {
        return id;
    }

    @Override
    public PcmFormat format() {
        return format;
    }

    @Override
    public int sizeInBytes() {
        return size;
    }

    /** Safe to call twice, and after the device closed (its buffers went with it). */
    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        owner.forget(this);
        if (owner.isReleased()) {
            return;
        }
        AL10.alDeleteBuffers(id);
        owner.logAlError("Couldn't delete an audio buffer");
    }
}
