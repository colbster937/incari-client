package incari.client.eagler;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * The player's voice chat settings, kept in {@code incari/voice.txt}: which channel to join
 * on servers that have voice, how loud others are, how loud the microphone is, and how far
 * proximity chat carries. Voice starts off: joining a channel shares the microphone, which is the
 * player's call.
 */
public final class VoiceSettings {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "voice.txt";

    /** Which voices the player hears and speaks to. */
    public enum Channel {
        OFF,
        /** Everyone on the server with voice on. */
        GLOBAL,
        /** Players nearby, louder the closer they are. */
        PROXIMITY
    }

    private final Path file;
    private Channel channel = Channel.OFF;
    private float listenVolume = 1.0F;
    private float microphoneVolume = 1.0F;
    private int range = 16;

    private VoiceSettings(Path file) {
        this.file = file;
    }

    static VoiceSettings load(Path gameDirectory) {
        VoiceSettings settings =
                new VoiceSettings(gameDirectory.resolve("incari").resolve(FILE));
        if (!Files.isRegularFile(settings.file)) {
            return settings;
        }
        try {
            for (String line : Files.readAllLines(settings.file, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split < 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                try {
                    switch (key) {
                        case "channel" -> settings.channel = Channel.valueOf(value.toUpperCase(Locale.ROOT));
                        case "listen" -> settings.listenVolume = clamp(Float.parseFloat(value), 0, 2);
                        case "microphone" -> settings.microphoneVolume = clamp(Float.parseFloat(value), 0, 2);
                        case "range" -> settings.range = (int) clamp(Integer.parseInt(value), MIN_RANGE, MAX_RANGE);
                        default -> {}
                    }
                } catch (IllegalArgumentException e) {
                    // A hand-edited line; keep the default.
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", settings.file, e.toString());
        }
        return settings;
    }

    /** The shortest and longest proximity range, in blocks. */
    public static final int MIN_RANGE = 5;

    public static final int MAX_RANGE = 32;

    public Channel channel() {
        return channel;
    }

    public float listenVolume() {
        return listenVolume;
    }

    public float microphoneVolume() {
        return microphoneVolume;
    }

    public int range() {
        return range;
    }

    void setChannel(Channel channel) {
        this.channel = channel;
        save();
    }

    void setListenVolume(float volume) {
        listenVolume = clamp(volume, 0, 2);
        save();
    }

    void setMicrophoneVolume(float volume) {
        microphoneVolume = clamp(volume, 0, 2);
        save();
    }

    void setRange(int blocks) {
        range = (int) clamp(blocks, MIN_RANGE, MAX_RANGE);
        save();
    }

    private void save() {
        String text = "channel=" + channel.name().toLowerCase(Locale.ROOT) + "\nlisten=" + listenVolume
                + "\nmicrophone=" + microphoneVolume + "\nrange=" + range + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
