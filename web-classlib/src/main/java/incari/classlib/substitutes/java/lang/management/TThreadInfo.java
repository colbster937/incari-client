package incari.classlib.substitutes.java.lang.management;

/**
 * {@code java.lang.management.ThreadInfo}, which TeaVM lacks. The browser build can't dump its
 * threads, so none is ever created; the type exists for the game's watchdog and crash reports.
 */
public class TThreadInfo {

    private TThreadInfo() {}

    public long getThreadId() {
        return 0;
    }

    public String getThreadName() {
        return "";
    }

    public StackTraceElement[] getStackTrace() {
        return new StackTraceElement[0];
    }

    @Override
    public String toString() {
        return "";
    }
}
