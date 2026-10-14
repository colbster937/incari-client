package incari.tools.webbuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The single HTML file's sounds, as Eaglercraft packs its offline download: every sound but the
 * music and the records (293 of the 377 MB), each encoded again smaller with ffmpeg, as Ogg Vorbis
 * at 22,050 Hz and quality 0. Their own channels are kept. The 84 MB left become about 38: the
 * file grows about 51 MB as base64. A game's sound effects keep their character at that rate. The
 * browser's decoder reads them as it reads the originals, and so does the game's own (JOrbis), the
 * fallback.
 *
 * <p>Encoded files are kept under the asset objects' folder ({@code small-sounds/}, by the
 * original's hash and the settings), so only the first build encodes. Without ffmpeg the file
 * carries {@link CoreSounds} instead, as they are.
 */
final class SmallSounds {

    /** The encoder's settings; part of the cache's folder name, so a change encodes again. */
    private static final List<String> SETTINGS = List.of("-ar", "22050", "-q:a", "0");

    private static final String CACHE = "small-sounds/vorbis-22050-q0";

    private static final List<String> LEFT_OUT = List.of("music/", "records/");

    private static final String SOUNDS = "minecraft/sounds/";

    private SmallSounds() {}

    /** Whether the asset index's {@code name} is a sound file the single file carries. */
    static boolean keeps(String name) {
        if (!name.startsWith(SOUNDS) || !name.endsWith(".ogg")) {
            return false;
        }
        String path = name.substring(SOUNDS.length());
        for (String folder : LEFT_OUT) {
            if (path.startsWith(folder)) {
                return false;
            }
        }
        return true;
    }

    /** Whether ffmpeg runs here, with its Vorbis encoder. */
    static boolean available() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true)
                    .start();
            String encoders = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && encoders.contains("libvorbis");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The sound object {@code hash} in {@code objects}, encoded smaller: from the cache beside
     * {@code objects} if it's there, else encoded and kept. The original if encoding didn't make it
     * smaller.
     */
    static byte[] encode(Path objects, String hash) throws IOException {
        Path source = objects.resolve(hash.substring(0, 2) + "/" + hash);
        Path cached = cacheOf(objects).resolve(hash.substring(0, 2) + "/" + hash + ".ogg");
        if (!Files.isRegularFile(cached)) {
            Files.createDirectories(cached.getParent());
            Path partial = cached.resolveSibling(hash + ".partial.ogg");
            List<String> command = new ArrayList<>(List.of(
                    "ffmpeg",
                    "-nostdin",
                    "-loglevel",
                    "error",
                    "-y",
                    "-i",
                    source.toString(),
                    "-map_metadata",
                    "-1",
                    "-vn",
                    "-c:a",
                    "libvorbis"));
            command.addAll(SETTINGS);
            command.addAll(List.of("-f", "ogg", partial.toString()));
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new IOException("ffmpeg couldn't encode " + source + ": " + output.strip());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted encoding " + source, e);
            }
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        byte[] smaller = Files.readAllBytes(cached);
        return smaller.length < Files.size(source) ? smaller : Files.readAllBytes(source);
    }

    private static Path cacheOf(Path objects) {
        Path parent = objects.toAbsolutePath().getParent();
        return (parent == null ? objects : parent).resolve(CACHE);
    }
}
