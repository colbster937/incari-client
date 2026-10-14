package incari.client.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import incari.client.tier.DeviceTier;
import incari.platform.api.Platforms;
import incari.platform.api.audio.AudioDecoder;
import incari.platform.api.audio.AudioException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Loads the game's sounds through the platform's own decoder, where it has one (the browser,
 * ADR 0013). {@code SoundBufferLibrary} asks here first; null sends it down its own path, the
 * game's Java decoder, which is all the desktop ever uses.
 *
 * <p>Whole sounds are decoded by the platform (natively, off the page's thread) and cached as the
 * game caches them, within the tier's budget ({@link DecodedSoundBudget}). Streams (music, records) aren't decoded here at all: the file goes to the
 * device, which decodes it as it plays ({@link CompressedSoundStream}). Files the page only has a
 * placeholder for are fetched first ({@link RemoteSounds}).
 *
 * <p>A sound that can't be read (its download failed: a dropped connection, a host refusing a burst
 * of requests) is played as a moment of silence and fetched again the next time it's asked for, a
 * few seconds later. The game only plays a channel once its buffer arrives and only reclaims a
 * channel that has played, so a failed future would leave that sound silent until the next
 * resource reload and hold one of its channels for good; enough of them and no new sound plays.
 */
public final class PlatformSoundLoading {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SoundLoader LOADER = new SoundLoader();

    static final DecodedSoundBudget BUDGET = new DecodedSoundBudget();

    /** How long a sound that couldn't be read stays silent before it's tried again. */
    static final long RETRY_MILLIS = 5000;

    private PlatformSoundLoading() {}

    /**
     * The decoded sound at {@code location}, cached in the game's own {@code cache}; null if the
     * platform leaves decoding to the game.
     */
    public static @Nullable CompletableFuture<SoundBuffer> completeBuffer(
            final Map<Identifier, CompletableFuture<SoundBuffer>> cache,
            final ResourceProvider resources,
            final Identifier location) {
        return completeBuffer(Platforms.current().audio().decoder(), cache, resources, location);
    }

    static @Nullable CompletableFuture<SoundBuffer> completeBuffer(
            final @Nullable AudioDecoder decoder,
            final Map<Identifier, CompletableFuture<SoundBuffer>> cache,
            final ResourceProvider resources,
            final Identifier location) {
        if (decoder == null) {
            return null;
        }
        CompletableFuture<SoundBuffer> buffer = cache.get(location);
        if (buffer != null
                && buffer.getNow(null) instanceof Silence silence
                && System.currentTimeMillis() - silence.failedAt >= RETRY_MILLIS) {
            buffer = null;
        }
        if (buffer == null) {
            buffer = CompletableFuture.supplyAsync(() -> decode(decoder, resources, location), LOADER)
                    .exceptionally(failure -> {
                        LOGGER.warn("Couldn't load the sound {}; trying again when it next plays", location, failure);
                        return new Silence(System.currentTimeMillis());
                    });
            cache.put(location, buffer);
            var _ = buffer.thenAccept(decoded -> BUDGET.decoded(location, decoded.size()));
        } else {
            BUDGET.used(location);
        }
        BUDGET.trim(cache, location, DecodedSoundBudget.budget(DeviceTier.current()));
        return buffer;
    }

    /**
     * What a sound that couldn't be read plays instead: a hundredth of a second of silence, so its
     * channel plays, finishes and goes back to the pool.
     */
    static final class Silence extends SoundBuffer {

        private static final int FRAMES = 480;

        final long failedAt;

        Silence(long failedAt) {
            super(
                    ByteBuffer.allocate(FRAMES * 2),
                    new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000, 16, 1, 2, 48000, false));
            this.failedAt = failedAt;
        }
    }

    /**
     * The file at {@code location} as a stream the device plays compressed; null if the platform
     * leaves decoding to the game.
     */
    public static @Nullable CompletableFuture<AudioStream> stream(
            final ResourceProvider resources, final Identifier location, final boolean looping) {
        return stream(Platforms.current().audio().decoder(), resources, location, looping);
    }

    static @Nullable CompletableFuture<AudioStream> stream(
            final @Nullable AudioDecoder decoder,
            final ResourceProvider resources,
            final Identifier location,
            final boolean looping) {
        if (decoder == null) {
            return null;
        }
        return CompletableFuture.supplyAsync(
                () -> new CompressedSoundStream(read(resources, location), looping), LOADER);
    }

    private static SoundBuffer decode(
            final AudioDecoder decoder, final ResourceProvider resources, final Identifier location) {
        byte[] file = read(resources, location);
        try {
            return new SoundBuffer(decoder.decodeOggVorbis(file));
        } catch (AudioException e) {
            // A browser without Ogg Vorbis (older Safari): the game's decoder still can.
            LOGGER.warn("The platform couldn't decode {} ({}); decoding it in Java", location, e.getMessage());
        }
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(file))) {
            ByteBuffer samples = stream.readAll();
            return new SoundBuffer(samples, stream.getFormat());
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't decode " + location, e);
        }
    }

    /** The file's bytes, fetched first if the page has only a placeholder for it. */
    private static byte[] read(final ResourceProvider resources, final Identifier location) {
        try {
            byte[] file;
            try (InputStream in = resources.open(location)) {
                file = in.readAllBytes();
            }
            if (file.length == 0) {
                byte[] fetched = RemoteSounds.fetch(location.getNamespace() + "/" + location.getPath());
                if (fetched != null) {
                    return fetched;
                }
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + location, e);
        }
    }
}
