package incari.app.web;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;

/**
 * The host page's loader ({@code src/main/ts/boot.ts}), as the game's start-up sees it: the
 * steps it shows under the progress bar, and what the build manifest says about the build. Without
 * the loader (an experiment's page), every call does nothing.
 */
final class LoaderPage {

    private LoaderPage() {}

    /** Shows a start-up step on the loading screen, and logs it. */
    static void status(String text) {
        System.out.println("[Incari] Loading: " + text);
        show(text);
    }

    @JSBody(params = "text", script = "var loader = window.incariLoader; if (loader) loader.status(text);")
    private static native void show(String text);

    /** The asset pack's URL (versioned by its hash) from the build manifest, or null if the build has none. */
    @JSBody(script = "var build = window.incariBuild; return build && build.pack ? build.pack : null;")
    static native @Nullable String packUrl();

    /**
     * The build's id from the build manifest (a hash over its files), or null without a manifest or
     * for a development build, whose id names no particular files.
     */
    @JSBody(
            script = "var build = window.incariBuild;"
                    + " return build && build.id && build.id !== 'development' ? build.id : null;")
    static native @Nullable String buildId();
}
