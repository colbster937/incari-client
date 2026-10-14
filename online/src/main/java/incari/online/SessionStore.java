package incari.online;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import incari.framework.config.ConfigStorage;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * The session token, kept between starts (ADR 0058): {@code session.json} under the client's
 * {@code incari/online}, which the browser stores behind in OPFS or IndexedDB (ADR 0011). The token,
 * never the password. The username is kept beside it, to show while the session resumes.
 *
 * <p>Game thread; the storage returns at once.
 */
final class SessionStore {

    static final String FILE = "session.json";

    /** A saved session. */
    record Saved(String username, String token) {
        @Override
        public String toString() {
            // Never the token, in a log or anywhere else.
            return "Saved[" + username + "]";
        }
    }

    private final ConfigStorage storage;
    private final Problems problems;

    /** Where a file that can't be read or written is reported. */
    interface Problems {
        void report(String message, @Nullable Throwable error);
    }

    SessionStore(ConfigStorage storage, Problems problems) {
        this.storage = storage;
        this.problems = problems;
    }

    /** The saved session, or null if there's none or it can't be read. */
    @Nullable
    Saved load() {
        try {
            String text = storage.read(FILE);
            if (text == null) {
                return null;
            }
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String username = json.has("username") ? json.get("username").getAsString() : "";
            String token = json.has("token") ? json.get("token").getAsString() : "";
            return token.isEmpty() ? null : new Saved(username, token);
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            problems.report("Couldn't read the saved Incari session; signed out", e);
            return null;
        }
    }

    void save(Saved session) {
        JsonObject json = new JsonObject();
        json.addProperty("username", session.username());
        json.addProperty("token", session.token());
        try {
            storage.write(FILE, json.toString());
        } catch (IOException e) {
            problems.report("Couldn't save the Incari session; you'll sign in again next time", e);
        }
    }

    void clear() {
        try {
            storage.delete(FILE);
        } catch (IOException e) {
            problems.report("Couldn't delete the saved Incari session", e);
        }
    }
}
