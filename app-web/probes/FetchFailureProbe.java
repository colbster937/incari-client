import java.io.IOException;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * A failed download, reported to a suspended coroutine from a promise's rejection handler: the
 * caller catches it and carries on, and the page doesn't crash.
 *
 * <p>Reporting it through {@code AsyncCallback.error} crashes the page: TeaVM's WebAssembly GC
 * runtime resumes the caller, which catches the exception, and then also rethrows it out of the
 * JavaScript callback, where it's an unhandled rejection. The page crashed this way on a missing
 * asset index (in the screenshot harness). So {@code incari.app.web.Fetch} and
 * {@code WebAudioDecoder} complete with a value that says it failed and throw in Java, as this
 * probe does; set {@link #USE_ERROR} to see TeaVM's behavior.
 */
public final class FetchFailureProbe {

    /** True to report the failure through {@code AsyncCallback.error}, which crashes the page. */
    private static final boolean USE_ERROR = false;

    @JSFunctor
    interface Failed extends JSObject {
        void failed(String message);
    }

    @JSBody(
            params = {"failed"},
            script = "Promise.reject(new Error('HTTP 404')).then(null, function(e) { failed(String(e)); });")
    private static native void start(Failed failed);

    @Async
    private static native String fetch() throws IOException;

    private static void fetch(AsyncCallback<String> callback) {
        start(message -> {
            if (USE_ERROR) {
                callback.error(new IOException("Couldn't fetch: " + message));
            } else {
                callback.complete("failed: " + message);
            }
        });
    }

    private static void fetchOrThrow() throws IOException {
        String outcome = fetch();
        if (outcome.startsWith("failed: ")) {
            throw new IOException("Couldn't fetch: " + outcome.substring("failed: ".length()));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            fetchOrThrow();
            System.out.println("probe failed: no exception");
            return;
        } catch (IOException e) {
            System.out.println("caught " + e.getMessage());
        }
        try {
            fetchOrThrow();
        } catch (IOException e) {
            System.out.println("caught again " + e.getMessage());
        }
        // A crash would come as the page's unhandled rejection, after the callback returns.
        Thread.sleep(2000);
        System.out.println("probe passed");
    }
}
