package incari.classlib;

/**
 * What {@code StackWalker.getCallerClass} answers in the browser build, which can't see the call
 * stack's classes. Loggers named after their caller get this name instead; the game's log format
 * doesn't print logger names.
 */
public final class UnknownCaller {

    private UnknownCaller() {}
}
