package incari.tools.build;

/** Console output for the CLI. Kept deliberately plain so CI logs stay readable. */
public final class Log {

    private Log() {}

    /** A top-level step the user is waiting on. */
    public static void step(String message) {
        System.out.println("==> " + message);
    }

    public static void info(String message) {
        System.out.println("    " + message);
    }

    public static void warn(String message) {
        System.err.println("warning: " + message);
    }
}
