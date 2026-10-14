package incari.app.web;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/** Downloads files from next to the page: before the game starts, and for its resource reloads. */
final class Fetch {

    private Fetch() {}

    /**
     * The whole file at {@code url}. Suspends the calling coroutine until the download finishes:
     * must not block the page.
     *
     * @param hint what to check if it fails, for the error message
     */
    static byte[] bytes(String url, String hint) throws IOException {
        Outcome outcome = fetch(url);
        ArrayBuffer buffer = outcome.buffer();
        if (buffer == null) {
            throw new IOException("Couldn't fetch " + url + ": " + outcome.error() + " (" + hint + ")");
        }
        return new Int8Array(buffer).copyToJavaArray();
    }

    /**
     * The whole file at {@code url}, tried up to {@code attempts} times while it fails for a reason
     * that may pass (no connection, a server error, too many requests), waiting longer each time.
     * Suspends the calling thread: never the page's own.
     *
     * @param hint what to check if it fails, for the error message
     */
    static byte[] bytesRetrying(String url, String hint, int attempts) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Outcome outcome = fetch(url);
            ArrayBuffer buffer = outcome.buffer();
            if (buffer != null) {
                return new Int8Array(buffer).copyToJavaArray();
            }
            String error = String.valueOf(outcome.error());
            if (attempt >= attempts || !passing(error)) {
                throw new IOException("Couldn't fetch " + url + ": " + error + " (" + hint + ")");
            }
            try {
                Thread.sleep(500L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted fetching " + url, e);
            }
        }
    }

    /** Whether a fetch's failure may pass: anything but a 4xx answer, except time-outs and rate limits. */
    static boolean passing(String error) {
        if (!error.startsWith("Error: HTTP 4")) {
            return true;
        }
        return error.equals("Error: HTTP 408") || error.equals("Error: HTTP 429");
    }

    /**
     * The whole files at {@code urls}, in their order, downloaded side by side. Suspends the calling
     * coroutine until all have finished: must not block the page.
     *
     * @param hint what to check if one fails, for the error message
     */
    static byte[][] all(List<String> urls, String hint) throws IOException {
        Outcome[] outcomes = fetchAll(urls.toArray(String[]::new));
        byte[][] files = new byte[outcomes.length][];
        for (int i = 0; i < outcomes.length; i++) {
            ArrayBuffer buffer = outcomes[i].buffer();
            if (buffer == null) {
                throw new IOException("Couldn't fetch " + urls.get(i) + ": " + outcomes[i].error() + " (" + hint + ")");
            }
            files[i] = new Int8Array(buffer).copyToJavaArray();
        }
        return files;
    }

    /**
     * A download's contents, or why it failed. A failure comes back as a value and is thrown here, not
     * through {@code AsyncCallback.error}: TeaVM's WebAssembly GC runtime rethrows an error it
     * resumes with out of the JavaScript callback as well, after the caller has caught it, and the
     * page crashes on the unhandled rejection (probe: {@code app-web/probes/FetchFailureProbe}).
     */
    private record Outcome(
            @Nullable ArrayBuffer buffer, @Nullable String error) {}

    @Async
    private static native Outcome fetch(String url);

    private static void fetch(String url, AsyncCallback<Outcome> callback) {
        start(
                url,
                buffer -> callback.complete(new Outcome(buffer, null)),
                message -> callback.complete(new Outcome(null, message)));
    }

    @Async
    private static native Outcome[] fetchAll(String[] urls);

    private static void fetchAll(String[] urls, AsyncCallback<Outcome[]> callback) {
        Outcome[] outcomes = new Outcome[urls.length];
        if (urls.length == 0) {
            callback.complete(outcomes);
            return;
        }
        int[] left = {urls.length};
        for (int i = 0; i < urls.length; i++) {
            int index = i;
            start(
                    urls[i],
                    buffer -> {
                        outcomes[index] = new Outcome(buffer, null);
                        if (--left[0] == 0) {
                            callback.complete(outcomes);
                        }
                    },
                    message -> {
                        outcomes[index] = new Outcome(null, message);
                        if (--left[0] == 0) {
                            callback.complete(outcomes);
                        }
                    });
        }
    }

    @JSFunctor
    interface Done extends JSObject {
        void done(ArrayBuffer buffer);
    }

    @JSFunctor
    interface Failed extends JSObject {
        void failed(String message);
    }

    @JSBody(
            params = {"url", "done", "failed"},
            script = "fetch(url).then(function(r) {"
                    + " if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })"
                    + ".then(function(b) { done(b); }, function(e) { failed(String(e)); });")
    private static native void start(String url, Done done, Failed failed);

    @JSBody(script = "return performance.now();")
    static native double now();
}
