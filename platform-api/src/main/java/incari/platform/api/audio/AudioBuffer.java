package incari.platform.api.audio;

/** PCM samples uploaded to the device, playable by any number of sources. */
public interface AudioBuffer extends AutoCloseable {

    /** The format the samples were uploaded in. */
    PcmFormat format();

    /** The size of the samples in bytes. */
    int sizeInBytes();

    /** Frees the buffer. A source still using it must be stopped or given another buffer first. */
    @Override
    void close();
}
