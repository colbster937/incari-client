package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmallSoundsTest {

    @TempDir
    Path dir;

    @Test
    void keepsEverySoundButTheMusicAndTheRecords() {
        assertThat(SmallSounds.keeps("minecraft/sounds/dig/stone1.ogg")).isTrue();
        assertThat(SmallSounds.keeps("minecraft/sounds/mob/pig/say1.ogg")).isTrue();
        assertThat(SmallSounds.keeps("minecraft/sounds/ambient/cave/cave1.ogg")).isTrue();
        assertThat(SmallSounds.keeps("minecraft/sounds/block/deepslate/break1.ogg"))
                .isTrue();

        assertThat(SmallSounds.keeps("minecraft/sounds/music/game/calm1.ogg")).isFalse();
        assertThat(SmallSounds.keeps("minecraft/sounds/records/cat.ogg")).isFalse();
        assertThat(SmallSounds.keeps("minecraft/sounds.json")).isFalse();
    }

    @Test
    void encodesSmallerOnceAndKeepsTheResult() throws Exception {
        assumeTrue(SmallSounds.available(), "ffmpeg with libvorbis");
        Path objects = dir.resolve("assets/objects");
        String hash = "ab" + "0".repeat(38);
        Path original = objects.resolve("ab/" + hash);
        Files.createDirectories(original.getParent());
        // Two seconds of a 48 kHz tone at a high quality: what the game's files are like.
        Process tone = new ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-loglevel",
                        "error",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:sample_rate=48000:duration=2",
                        "-c:a",
                        "libvorbis",
                        "-q:a",
                        "8",
                        "-f",
                        "ogg",
                        original.toString())
                .inheritIO()
                .start();
        assertThat(tone.waitFor()).isZero();

        byte[] smaller = SmallSounds.encode(objects, hash);
        assertThat(smaller.length).isLessThan((int) Files.size(original));
        assertThat(new String(smaller, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("OggS");
        Path cached = dir.resolve("assets/small-sounds/vorbis-22050-q0/ab/" + hash + ".ogg");
        assertThat(cached).exists();

        // The second time from the cache, not ffmpeg.
        Files.write(cached, smaller);
        assertThat(SmallSounds.encode(objects, hash)).isEqualTo(smaller);
    }
}
