package incari.client.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.audio.SoundBuffer;
import incari.platform.api.audio.AudioDecoder;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.DecodedAudio;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlatformSoundLoadingTest {

    private static final Identifier CLICK = Identifier.withDefaultNamespace("sounds/random/click.ogg");

    /** A decoder that hands back the file's length as frames, and remembers what it was given. */
    private static final class FakeDecoder implements AudioDecoder {
        final List<byte[]> files = new ArrayList<>();

        @Override
        public DecodedAudio decodeOggVorbis(byte[] file) {
            files.add(file);
            if (file.length > 0 && file[0] == 'X') {
                throw new AudioException("not Ogg");
            }
            return new DecodedAudio() {
                @Override
                public int sampleRate() {
                    return 48000;
                }

                @Override
                public int channels() {
                    return 1;
                }

                @Override
                public int frames() {
                    return file.length;
                }

                @Override
                public int sizeInBytes() {
                    return file.length * Float.BYTES;
                }
            };
        }
    }

    /** Resources from a map of paths to contents. */
    private static ResourceProvider files(Map<Identifier, byte[]> files) {
        return new ResourceProvider() {
            @Override
            public Optional<Resource> getResource(Identifier location) {
                return Optional.empty();
            }

            @Override
            public InputStream open(Identifier location) throws FileNotFoundException {
                byte[] file = files.get(location);
                if (file == null) {
                    throw new FileNotFoundException(location.toString());
                }
                return new ByteArrayInputStream(file);
            }
        };
    }

    @AfterEach
    void removeRemoteSource() {
        RemoteSounds.install(null);
    }

    @Test
    void leavesDecodingToTheGameWithoutAPlatformDecoder() {
        assertThat(PlatformSoundLoading.completeBuffer(null, new HashMap<>(), files(Map.of()), CLICK))
                .isNull();
        assertThat(PlatformSoundLoading.stream(null, files(Map.of()), CLICK, false))
                .isNull();
    }

    @Test
    void decodesThroughThePlatformAndCachesTheResult() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = new HashMap<>();
        ResourceProvider resources = files(Map.of(CLICK, new byte[] {1, 2, 3}));

        CompletableFuture<SoundBuffer> first = PlatformSoundLoading.completeBuffer(decoder, cache, resources, CLICK);
        SoundBuffer buffer = first.get(10, TimeUnit.SECONDS);
        assertThat(buffer.size()).isEqualTo(3 * Float.BYTES);
        assertThat(buffer.format().getChannels()).isEqualTo(1);
        assertThat(buffer.isValid()).isTrue();

        assertThat(PlatformSoundLoading.completeBuffer(decoder, cache, resources, CLICK))
                .isSameAs(first);
        assertThat(cache).containsKey(CLICK);
        assertThat(decoder.files).hasSize(1);
    }

    @Test
    void fetchesPlaceholdersByTheirAssetName() throws Exception {
        List<String> asked = new ArrayList<>();
        RemoteSounds.install(name -> {
            asked.add(name);
            return new byte[] {9, 9, 9, 9, 9};
        });
        FakeDecoder decoder = new FakeDecoder();
        ResourceProvider resources = files(Map.of(CLICK, new byte[0]));

        SoundBuffer buffer = PlatformSoundLoading.completeBuffer(decoder, new HashMap<>(), resources, CLICK)
                .get(10, TimeUnit.SECONDS);
        assertThat(asked).containsExactly("minecraft/sounds/random/click.ogg");
        assertThat(buffer.size()).isEqualTo(5 * Float.BYTES);

        // A file that's really there (a resource pack's) is never fetched.
        asked.clear();
        PlatformSoundLoading.completeBuffer(decoder, new HashMap<>(), files(Map.of(CLICK, new byte[] {1})), CLICK)
                .get(10, TimeUnit.SECONDS);
        assertThat(asked).isEmpty();
    }

    @Test
    void playsSilenceWhenNeitherDecoderCanRead() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        // Not Ogg for the platform, and not for the game's decoder either.
        ResourceProvider resources =
                files(Map.of(CLICK, "XXXXXXXXXXXXXXXX".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        SoundBuffer unreadable = PlatformSoundLoading.completeBuffer(decoder, new HashMap<>(), resources, CLICK)
                .get(10, TimeUnit.SECONDS);
        assertThat(unreadable).isInstanceOf(PlatformSoundLoading.Silence.class);
        assertThat(unreadable.isValid()).isTrue();

        SoundBuffer missing = PlatformSoundLoading.completeBuffer(decoder, new HashMap<>(), files(Map.of()), CLICK)
                .get(10, TimeUnit.SECONDS);
        assertThat(missing).isInstanceOf(PlatformSoundLoading.Silence.class);
    }

    @Test
    void triesAFailedDownloadAgainOnlyAfterAWhile() throws Exception {
        int[] tries = {0};
        RemoteSounds.install(name -> {
            if (++tries[0] == 1) {
                throw new java.io.IOException("Failed to fetch");
            }
            return new byte[] {7, 7};
        });
        FakeDecoder decoder = new FakeDecoder();
        Map<Identifier, CompletableFuture<SoundBuffer>> cache = new HashMap<>();
        ResourceProvider resources = files(Map.of(CLICK, new byte[0]));

        CompletableFuture<SoundBuffer> failed = PlatformSoundLoading.completeBuffer(decoder, cache, resources, CLICK);
        assertThat(failed.get(10, TimeUnit.SECONDS)).isInstanceOf(PlatformSoundLoading.Silence.class);
        // Asked for again at once: still the silence, not another download.
        assertThat(PlatformSoundLoading.completeBuffer(decoder, cache, resources, CLICK))
                .isSameAs(failed);
        assertThat(tries[0]).isEqualTo(1);

        // Long enough after the failure, it's fetched again.
        cache.put(CLICK, CompletableFuture.completedFuture(new PlatformSoundLoading.Silence(0)));
        SoundBuffer fetched = PlatformSoundLoading.completeBuffer(decoder, cache, resources, CLICK)
                .get(10, TimeUnit.SECONDS);
        assertThat(fetched).isNotInstanceOf(PlatformSoundLoading.Silence.class);
        assertThat(fetched.size()).isEqualTo(2 * Float.BYTES);
        assertThat(tries[0]).isEqualTo(2);
    }

    @Test
    void streamsHandTheWholeFileOver() throws Exception {
        byte[] file = {4, 5, 6};
        AudioStream stream = PlatformSoundLoading.stream(new FakeDecoder(), files(Map.of(CLICK, file)), CLICK, true)
                .get(10, TimeUnit.SECONDS);
        assertThat(stream).isInstanceOf(CompressedSoundStream.class);
        CompressedSoundStream compressed = (CompressedSoundStream) stream;
        assertThat(compressed.file()).containsExactly(4, 5, 6);
        assertThat(compressed.looping()).isTrue();
        compressed.close();
    }
}
