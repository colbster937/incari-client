package incari.platform.web.audio;

import org.jspecify.annotations.Nullable;

/**
 * Reads the channel count and sample rate from an Ogg Vorbis file's identification header, which
 * is the first packet of the first page. A stream played by a media element is positioned (mono)
 * or not (stereo) from the moment it's attached, before the browser has read any of it.
 */
final class OggVorbisInfo {

    /** "OggS", version, header type, granule position, serial, sequence, checksum, segment count. */
    private static final int PAGE_HEADER = 27;

    /** Packet type 1, "vorbis", a 4-byte version, then the channels and the rate. */
    private static final int CHANNELS = 11;

    private static final int RATE = 12;

    private final int channels;
    private final int sampleRate;

    private OggVorbisInfo(int channels, int sampleRate) {
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    int channels() {
        return channels;
    }

    int sampleRate() {
        return sampleRate;
    }

    /** The file's header, or null if it doesn't start like an Ogg Vorbis file. */
    static @Nullable OggVorbisInfo read(byte[] file) {
        if (file.length < PAGE_HEADER || file[0] != 'O' || file[1] != 'g' || file[2] != 'g' || file[3] != 'S') {
            return null;
        }
        int packet = PAGE_HEADER + (file[PAGE_HEADER - 1] & 0xFF);
        if (file.length < packet + RATE + 4 || file[packet] != 1) {
            return null;
        }
        String magic = "vorbis";
        for (int i = 0; i < magic.length(); i++) {
            if (file[packet + 1 + i] != magic.charAt(i)) {
                return null;
            }
        }
        int channels = file[packet + CHANNELS] & 0xFF;
        int rate = (file[packet + RATE] & 0xFF)
                | (file[packet + RATE + 1] & 0xFF) << 8
                | (file[packet + RATE + 2] & 0xFF) << 16
                | (file[packet + RATE + 3] & 0xFF) << 24;
        return channels > 0 && rate > 0 ? new OggVorbisInfo(channels, rate) : null;
    }
}
