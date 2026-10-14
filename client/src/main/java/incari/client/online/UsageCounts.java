package incari.client.online;

import incari.client.IncariBuild;
import incari.client.settings.ClientSettings;
import incari.online.Failure;
import incari.online.OnlineService;
import incari.platform.api.Platforms;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import net.minecraft.util.Util;

/**
 * Usage counts (ADR 0062): once a UTC day, the online service hears that this install ran, with a
 * random id made for the install (never the account's), the version and the build. The player
 * turns it off in Incari Settings; off, nothing is sent. Game thread.
 */
final class UsageCounts {

    private static final String INSTALL = "install-id";
    private static final String LAST = "last-count";

    /** How long after a count that got no answer it's tried again. */
    private static final long OFFLINE_RETRY_MILLIS = 60_000;

    /**
     * How long after a limited count (the service takes a few a connection, and so many an hour
     * from one network) it's tried again.
     */
    private static final long LIMITED_RETRY_MILLIS = 30 * 60_000;

    /** True while a count is out, so a slow answer isn't sent again each tick. */
    private static boolean sending;

    /** No count goes before this ({@link Util#getMillis}): set after one that didn't get through. */
    private static long retryAt;

    private UsageCounts() {}

    /** From {@link IncariOnline#tick}: sends today's count once the service has said hello. */
    static void tick(OnlineService online) {
        if (sending || !online.greeted() || !ClientSettings.USAGE_COUNTS.get() || Util.getMillis() < retryAt) {
            return;
        }
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (today.equals(ClientSettings.value(LAST))) {
            return;
        }
        sending = true;
        online.count(install(), IncariBuild.VERSION, Platforms.current().buildKind(), result -> {
            sending = false;
            Failure failure = result.failure();
            if (failure == Failure.OFFLINE) {
                // No answer: the connection dropped or the service is slow. Soon, on a later one.
                retryAt = Util.getMillis() + OFFLINE_RETRY_MILLIS;
            } else if (failure == Failure.RATE_LIMITED) {
                retryAt = Util.getMillis() + LIMITED_RETRY_MILLIS;
            } else {
                // Counted, or turned down for good (a version it won't take, its own trouble):
                // sending again today wouldn't change that, and each try uses up its limits.
                ClientSettings.put(LAST, today);
            }
        });
    }

    /** The install's id: 16 random bytes (a random UUID's), base64url without padding, made the first time. */
    private static String install() {
        String id = ClientSettings.value(INSTALL);
        if (id != null && id.length() == 22) {
            return id;
        }
        // A random UUID's bits: the browser build has no SecureRandom, and the id only has to be
        // unique, not secret (it's hashed by the service and tied to nothing).
        UUID random = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(random.getMostSignificantBits())
                .putLong(random.getLeastSignificantBits())
                .array();
        id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ClientSettings.put(INSTALL, id);
        return id;
    }
}
