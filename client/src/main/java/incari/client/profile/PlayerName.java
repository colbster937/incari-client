package incari.client.profile;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The name this player plays under offline, as Eagler clients let them choose it: kept in {@code
 * incari/profile.properties} in the game directory, and answered in place of the launch's name
 * wherever the game asks who the player is ({@code Minecraft.getUser}), with the offline UUID that
 * name has, as offline servers work it out. A launch with a real account (any access token but
 * {@code 0}) keeps its own.
 *
 * <p>An entry point that names the player itself (a page's {@code ?name=}, the desktop's tests)
 * calls {@link #fixAtLaunch}: the saved name is ignored and the profile screen doesn't open at
 * start-up.
 */
public final class PlayerName {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The access token offline launches pass. */
    private static final String OFFLINE_TOKEN = "0";

    private static volatile boolean fixedAtLaunch;

    // Set once, on the first question (in the game's constructor).
    private static @Nullable User launched;
    private static @Nullable Path file;
    /** What {@code getUser} answers. */
    private static volatile @Nullable User current;

    private PlayerName() {}

    /** The entry point named the player: that name stands, and nothing asks for another. */
    public static void fixAtLaunch() {
        fixedAtLaunch = true;
    }

    /** Whether the profile screen opens at start-up: offline, with the name the player's to choose. */
    public static boolean asksAtStartup() {
        User user = launched;
        return !fixedAtLaunch && user != null && offline(user);
    }

    /**
     * Hook ({@code Minecraft.getUser}): the player, as {@code launched} or under the name they
     * chose. Called often; after the first call it's a field read.
     */
    public static User user(User launchedUser, File gameDirectory) {
        User user = current;
        return user != null ? user : first(launchedUser, gameDirectory.toPath());
    }

    private static synchronized User first(User launchedUser, Path gameDirectory) {
        User user = current;
        if (user != null) {
            return user;
        }
        launched = launchedUser;
        file = gameDirectory.resolve("incari").resolve("profile.properties");
        String saved = fixedAtLaunch || !offline(launchedUser) ? null : read(file);
        user = saved == null ? launchedUser : named(launchedUser, saved);
        current = user;
        return user;
    }

    /** Whether the player can choose their name: they play offline. */
    public static boolean editable() {
        User user = launched;
        return user != null && offline(user);
    }

    /** The name the player plays under. */
    public static String name() {
        User user = current;
        return user != null ? user.getName() : "";
    }

    /** Plays as {@code name} from now on, and keeps it for the next start. */
    public static synchronized void set(String name) {
        User user = launched;
        if (!isValid(name) || user == null || !offline(user)) {
            throw new IllegalArgumentException("Can't play as " + name);
        }
        current = named(user, name);
        Path target = file;
        if (target != null) {
            try {
                Files.createDirectories(target.getParent());
                // Written by hand: the browser build's Properties can read but not store.
                Files.writeString(
                        target,
                        "# Incari: the name this player plays under offline\nname=" + name + "\n",
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Couldn't save the player's name", e);
            }
        }
    }

    /** A name every server takes: 3 to 16 letters, digits and underscores, as Eagler requires. */
    public static boolean isValid(String name) {
        return name.length() >= MIN_LENGTH
                && name.length() <= MAX_LENGTH
                && filter(name).equals(name);
    }

    /** {@code text} with what a name can't hold left out. */
    public static String filter(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    /** Forgets everything, as a new start would. Tests only. */
    static synchronized void reset() {
        fixedAtLaunch = false;
        launched = null;
        file = null;
        current = null;
    }

    private static boolean offline(User user) {
        return OFFLINE_TOKEN.equals(user.getAccessToken());
    }

    private static User named(User launchedUser, String name) {
        if (name.equals(launchedUser.getName())) {
            return launchedUser;
        }
        return new User(
                name,
                UUIDUtil.createOfflinePlayerUUID(name),
                launchedUser.getAccessToken(),
                launchedUser.getXuid(),
                launchedUser.getClientId());
    }

    private static @Nullable String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}", file, e);
            return null;
        }
        String name = properties.getProperty("name", "");
        return isValid(name) ? name : null;
    }
}
