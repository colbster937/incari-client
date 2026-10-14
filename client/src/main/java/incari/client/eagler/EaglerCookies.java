package incari.client.eagler;

import com.mojang.logging.LogUtils;
import incari.net.WebSocketUrl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Eagler server cookies (ADR 0019): up to 255 bytes a server stores in the client, for at
 * most a week, and gets back with the next login. Auth plugins use them as session tokens, so a
 * player logs in once rather than every join. A server chooses whether its cookie outlives the
 * session; those are kept in {@code incari/eagler-cookies.txt}, the rest in memory only.
 *
 * <p>A cookie goes back only to the server that set it: the key is the server's WebSocket URL.
 */
public final class EaglerCookies {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "eagler-cookies.txt";
    /** The longest a server may keep a cookie, as Eagler's clients hold it to. */
    private static final long MAX_SECONDS = TimeUnit.DAYS.toSeconds(7);

    private static final int MAX_BYTES = 255;

    /** One server's cookie: its bytes, when it expires, whether it's kept on disk. */
    private static final class Cookie {
        final byte[] data;
        final long expiresMillis;
        final boolean onDisk;

        Cookie(byte[] data, long expiresMillis, boolean onDisk) {
            this.data = data;
            this.expiresMillis = expiresMillis;
            this.onDisk = onDisk;
        }
    }

    private static final Object LOCK = new Object();
    private static @Nullable EaglerCookies instance;

    private final Path file;
    private final Map<String, Cookie> cookies = new HashMap<>();

    private EaglerCookies(Path file) {
        this.file = file;
    }

    /** The store for the game in {@code gameDirectory}, loaded the first time it's asked for. */
    public static EaglerCookies get(Path gameDirectory) {
        synchronized (LOCK) {
            EaglerCookies store = instance;
            if (store == null) {
                store = new EaglerCookies(gameDirectory.resolve("incari").resolve(FILE));
                store.load();
                instance = store;
            }
            return store;
        }
    }

    /** The cookie {@code server} set, if it hasn't expired. */
    public byte @Nullable [] cookie(String server) {
        synchronized (LOCK) {
            Cookie cookie = cookies.get(key(server));
            if (cookie == null) {
                return null;
            }
            if (System.currentTimeMillis() > cookie.expiresMillis) {
                cookies.remove(key(server));
                if (cookie.onDisk) {
                    save();
                }
                return null;
            }
            return cookie.data.clone();
        }
    }

    /**
     * {@code server} sets its cookie: {@code data} for {@code seconds}, kept on disk if {@code
     * onDisk}. Empty data, a negative lifetime, or one past the limits clears it instead.
     */
    public void set(String server, byte[] data, long seconds, boolean onDisk) {
        synchronized (LOCK) {
            Cookie previous;
            if (data.length == 0 || seconds < 0 || seconds > MAX_SECONDS || data.length > MAX_BYTES) {
                if (seconds > MAX_SECONDS || data.length > MAX_BYTES) {
                    LOGGER.warn(
                            "{} set a cookie past Eagler's limits ({} bytes, {} s); cleared",
                            server,
                            data.length,
                            seconds);
                }
                previous = cookies.remove(key(server));
            } else {
                long expires = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
                previous = cookies.put(key(server), new Cookie(data.clone(), expires, onDisk));
            }
            if (onDisk || (previous != null && previous.onDisk)) {
                save();
            }
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 3) {
                    continue;
                }
                long expires = Long.parseLong(parts[1]);
                if (expires > now) {
                    cookies.put(parts[0], new Cookie(unhex(parts[2]), expires, true));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}: {}", file, e.toString());
        }
    }

    private void save() {
        StringBuilder text = new StringBuilder();
        cookies.forEach((server, cookie) -> {
            if (cookie.onDisk) {
                text.append(server).append('\t').append(cookie.expiresMillis).append('\t');
                for (byte b : cookie.data) {
                    text.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
                }
                text.append('\n');
            }
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** The server's URL as the client writes it, so "ws://host:1" and "ws://HOST:1/" are one. */
    private static String key(String server) {
        WebSocketUrl url = WebSocketUrl.tryParse(server.trim());
        return (url != null ? url.toString() : server.trim()).toLowerCase(Locale.ROOT);
    }
}
