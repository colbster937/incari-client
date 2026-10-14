package incari.tools.webbuild;

import org.teavm.tooling.TeaVMToolLog;

/** TeaVM's progress on the console: warnings and errors always, the rest with --verbose. */
final class ConsoleLog implements TeaVMToolLog {

    private final boolean verbose;

    ConsoleLog(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void info(String text) {
        if (verbose) {
            System.out.println("    " + text);
        }
    }

    @Override
    public void debug(String text) {
        if (verbose) {
            System.out.println("    " + text);
        }
    }

    @Override
    public void warning(String text) {
        if (verbose) {
            System.err.println("warning: " + text);
        }
    }

    @Override
    public void error(String text) {
        System.err.println("error: " + text);
    }

    @Override
    public void info(String text, Throwable e) {
        info(text + ": " + e);
    }

    @Override
    public void debug(String text, Throwable e) {
        debug(text + ": " + e);
    }

    @Override
    public void warning(String text, Throwable e) {
        warning(text + ": " + e);
    }

    @Override
    public void error(String text, Throwable e) {
        error(text + ": " + e);
    }
}
