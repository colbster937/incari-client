package incari.classlib.logging;

import java.io.PrintStream;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J logger that prints to the browser console in the game's desktop log format:
 * {@code [12:34:56] [Render thread/INFO]: message}. Warnings and errors go to standard error,
 * which the browser shows as console errors. Debug and trace are off.
 */
public final class ConsoleLogger extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;

    // The console as it was when logging started. The game's Bootstrap.wrapStreams later points
    // System.out and System.err at loggers; printing to those would log each line forever.
    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    ConsoleLogger(String name) {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    protected @Nullable String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level,
            @Nullable Marker marker,
            @Nullable String messagePattern,
            @Nullable Object @Nullable [] arguments,
            @Nullable Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        PrintStream out = level.toInt() >= Level.WARN.toInt() ? ERR : OUT;
        Calendar now = Calendar.getInstance();
        out.println("[" + twoDigits(now.get(Calendar.HOUR_OF_DAY)) + ":" + twoDigits(now.get(Calendar.MINUTE)) + ":"
                + twoDigits(now.get(Calendar.SECOND)) + "] ["
                + Thread.currentThread().getName() + "/" + level + "]: "
                + message);
        if (throwable != null) {
            printWithCauses(throwable, out);
        }
    }

    /**
     * Prints a throwable and its causes, each found through {@code getCause()}. TeaVM's
     * {@code printStackTrace} reads the cause field instead, so it left out the cause of the game's
     * {@code ReportedException}, which overrides {@code getCause()} to answer its crash report's
     * exception: every crash logged without the error that caused it.
     */
    static void printWithCauses(Throwable throwable, PrintStream out) {
        Set<Throwable> printed = Collections.newSetFromMap(new IdentityHashMap<>());
        String heading = "";
        for (Throwable current = throwable; current != null && printed.add(current); current = current.getCause()) {
            out.println(heading + current);
            for (StackTraceElement element : current.getStackTrace()) {
                out.println("\tat " + element);
            }
            heading = "Caused by: ";
        }
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
