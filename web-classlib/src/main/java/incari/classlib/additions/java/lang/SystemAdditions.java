package incari.classlib.additions.java.lang;

/** {@code java.lang.System} methods TeaVM lacks. */
public final class SystemAdditions {

    private SystemAdditions() {}

    /**
     * A page can't end its own process. Exiting stops the calling thread for good (the game's main
     * thread, in practice), which halts the game where a desktop JVM would have exited; the page
     * stays open with the console log.
     */
    @SuppressWarnings("InfiniteLoopStatement") // Never returning is the point.
    public static void exit(int status) {
        // tools/webbuild's BrowserCheck watches for this line.
        System.err.println("Java exited with status " + status);
        Object never = new Object();
        synchronized (never) {
            while (true) {
                try {
                    never.wait();
                } catch (InterruptedException e) {
                    // Still exited.
                }
            }
        }
    }

    /** No native libraries in a page; the name is only ever logged or compared. */
    public static String mapLibraryName(String name) {
        return name;
    }
}
