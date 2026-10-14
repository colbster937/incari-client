package incari.platform.desktop.audio;

import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.PcmFormat;
import java.util.Arrays;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTOutputLimiter;

/**
 * The decisions around OpenAL calls that don't need a device: which format enum to upload with,
 * which context attributes to ask for, what the device's attributes say.
 *
 * <p>Only LWJGL's {@code static final int} constants appear here, and javac inlines those, so
 * this class loads without LWJGL on the class path. That keeps it unit-testable (LWJGL is
 * compile-only in this module).
 */
final class OpenAlSupport {

    /** How many sources to assume when the device doesn't say (OpenAL's minimum guarantee is lower). */
    static final int FALLBACK_SOURCE_COUNT = 30;

    private OpenAlSupport() {}

    /** The {@code AL_FORMAT_*} enum for samples in {@code format}. */
    static int alFormat(PcmFormat format) {
        boolean mono = format.channels() == 1;
        return switch (format.bitsPerSample()) {
            case 8 -> mono ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_STEREO8;
            case 16 -> mono ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            // PcmFormat's constructor already rejects anything else.
            default -> throw new AudioException("No OpenAL format for " + format);
        };
    }

    /**
     * The attribute list for {@code alcCreateContext}: key/value pairs ending in a 0.
     *
     * @param hrtfCount how many HRTF data sets the device offers; HRTF is only requested (either
     *     way) when there is at least one, so a device without HRTF isn't asked about it
     * @param spatialize whether the player wants HRTF on
     * @param limiterSupported whether ALC_SOFT_output_limiter is present. The limiter keeps many
     *     loud sources from clipping, which the game relies on: it never mixes gain down itself.
     */
    static int[] contextAttributes(int hrtfCount, boolean spatialize, boolean limiterSupported) {
        int[] attributes = new int[7];
        int next = 0;
        if (hrtfCount > 0) {
            attributes[next++] = SOFTHRTF.ALC_HRTF_SOFT;
            attributes[next++] = spatialize ? ALC10.ALC_TRUE : ALC10.ALC_FALSE;
            // The first HRTF the device lists: OpenAL Soft orders them by preference.
            attributes[next++] = SOFTHRTF.ALC_HRTF_ID_SOFT;
            attributes[next++] = 0;
        }
        if (limiterSupported) {
            attributes[next++] = SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT;
            attributes[next++] = ALC10.ALC_TRUE;
        }
        attributes[next++] = 0;
        return Arrays.copyOf(attributes, next);
    }

    /**
     * How many mono sources the context was given, from an {@code ALC_ALL_ATTRIBUTES} list
     * (key/value pairs, ending at a 0 key or the array's end), or {@link #FALLBACK_SOURCE_COUNT}
     * if the list doesn't say. Mono sources are the positional ones, which is what the game plays.
     */
    static int monoSourceCount(int[] attributes) {
        for (int i = 0; i + 1 < attributes.length; i += 2) {
            int key = attributes[i];
            if (key == 0) {
                break;
            }
            if (key == ALC11.ALC_MONO_SOURCES && attributes[i + 1] > 0) {
                return attributes[i + 1];
            }
        }
        return FALLBACK_SOURCE_COUNT;
    }

    /** A readable name for an ALC error code, for exception messages. */
    static String alcErrorName(int error) {
        return switch (error) {
            case ALC10.ALC_INVALID_DEVICE -> "invalid device";
            case ALC10.ALC_INVALID_CONTEXT -> "invalid context";
            case ALC10.ALC_INVALID_ENUM -> "invalid enum";
            case ALC10.ALC_INVALID_VALUE -> "invalid value";
            case ALC10.ALC_OUT_OF_MEMORY -> "out of memory";
            default -> "error 0x" + Integer.toHexString(error);
        };
    }

    /** A readable name for an AL error code, for log messages. */
    static String alErrorName(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "invalid name";
            case AL10.AL_INVALID_ENUM -> "invalid enum";
            case AL10.AL_INVALID_VALUE -> "invalid value";
            case AL10.AL_INVALID_OPERATION -> "invalid operation";
            case AL10.AL_OUT_OF_MEMORY -> "out of memory";
            default -> "error 0x" + Integer.toHexString(error);
        };
    }
}
