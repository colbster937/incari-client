package incari.classlib.substitutes.javax.sound.sampled;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * {@code javax.sound.sampled.AudioFormat}, which TeaVM lacks: the description of a sound's samples
 * that the game's Ogg decoder returns and its sound buffers keep. Only the description;
 * nothing here plays sound.
 */
public class TAudioFormat {

    /** {@code AudioSystem.NOT_SPECIFIED}. */
    private static final int NOT_SPECIFIED = -1;

    protected Encoding encoding;
    protected float sampleRate;
    protected int sampleSizeInBits;
    protected int channels;
    protected int frameSize;
    protected float frameRate;
    protected boolean bigEndian;

    public TAudioFormat(
            Encoding encoding,
            float sampleRate,
            int sampleSizeInBits,
            int channels,
            int frameSize,
            float frameRate,
            boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
    }

    /** Linear PCM: signed or unsigned, one frame per sample time, frames as wide as their samples. */
    public TAudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this(
                signed ? Encoding.PCM_SIGNED : Encoding.PCM_UNSIGNED,
                sampleRate,
                sampleSizeInBits,
                channels,
                channels == NOT_SPECIFIED || sampleSizeInBits == NOT_SPECIFIED
                        ? NOT_SPECIFIED
                        : (sampleSizeInBits + 7) / 8 * channels,
                sampleRate,
                bigEndian);
    }

    public Encoding getEncoding() {
        return encoding;
    }

    public float getSampleRate() {
        return sampleRate;
    }

    public int getSampleSizeInBits() {
        return sampleSizeInBits;
    }

    public int getChannels() {
        return channels;
    }

    public int getFrameSize() {
        return frameSize;
    }

    public float getFrameRate() {
        return frameRate;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "%s %s Hz, %d bit, %d channels, %d bytes/frame, %s",
                encoding,
                sampleRate == NOT_SPECIFIED ? "unknown" : Float.toString(sampleRate),
                sampleSizeInBits,
                channels,
                frameSize,
                bigEndian ? "big-endian" : "little-endian");
    }

    /** {@code AudioFormat.Encoding}: how samples are represented, compared by name. */
    public static class Encoding {

        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");
        public static final Encoding ULAW = new Encoding("ULAW");
        public static final Encoding ALAW = new Encoding("ALAW");

        private final String name;

        public Encoding(String name) {
            this.name = name;
        }

        @Override
        public final boolean equals(@Nullable Object other) {
            return other instanceof Encoding encoding && name.equals(encoding.name);
        }

        @Override
        public final int hashCode() {
            return name.hashCode();
        }

        @Override
        public final String toString() {
            return name;
        }
    }
}
