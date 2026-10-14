package incari.client.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import org.jspecify.annotations.Nullable;

/**
 * A whole Ogg Vorbis file, handed to the device to stream (music, records) instead of being
 * decoded a second at a time on the game's side. A device that can't stream compressed files still
 * gets PCM: the game's own decoder starts on the first {@link #read}.
 */
public final class CompressedSoundStream implements AudioStream {

    private final byte[] file;
    private final boolean looping;
    private @Nullable AudioStream decoder;

    public CompressedSoundStream(byte[] file, boolean looping) {
        this.file = file;
        this.looping = looping;
    }

    /** The whole file, not a copy (a track of music is megabytes): the caller mustn't change it. */
    public byte[] file() {
        return file;
    }

    public boolean looping() {
        return looping;
    }

    @Override
    public AudioFormat getFormat() {
        try {
            return decoder().getFormat();
        } catch (IOException e) {
            throw new IllegalStateException("Can't read the sound's header", e);
        }
    }

    @Override
    public ByteBuffer read(final int expectedSize) throws IOException {
        return decoder().read(expectedSize);
    }

    private AudioStream decoder() throws IOException {
        AudioStream current = decoder;
        if (current == null) {
            ByteArrayInputStream input = new ByteArrayInputStream(file);
            current = looping ? new LoopingAudioStream(JOrbisAudioStream::new, input) : new JOrbisAudioStream(input);
            decoder = current;
        }
        return current;
    }

    @Override
    public void close() throws IOException {
        AudioStream current = decoder;
        if (current != null) {
            current.close();
        }
    }
}
