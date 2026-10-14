package incari.platform.api.audio;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The output devices available.
 *
 * @param defaultDevice the system default's name, or null if there is no device at all
 * @param all every device's name, the default included
 */
public record AudioDeviceList(@Nullable String defaultDevice, List<String> all) {

    public static final AudioDeviceList EMPTY = new AudioDeviceList(null, List.of());

    public AudioDeviceList {
        all = List.copyOf(all);
    }
}
