package incari.classlib.additions.java.nio.file;

import incari.classlib.InstanceMethod;
import incari.classlib.util.Globs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.util.regex.Pattern;

/** {@code java.nio.file.FileSystem} methods TeaVM lacks. */
public final class FileSystemAdditions {

    private FileSystemAdditions() {}

    /** Matches paths against {@code glob:} or {@code regex:} patterns, as the JDK documents. */
    @InstanceMethod
    public static PathMatcher getPathMatcher(FileSystem self, String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        Pattern regex;
        if (syntax.equalsIgnoreCase("glob")) {
            regex = Pattern.compile(Globs.toRegex(pattern));
        } else if (syntax.equalsIgnoreCase("regex")) {
            regex = Pattern.compile(pattern);
        } else {
            throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
        }
        return path -> regex.matcher(path.toString()).matches();
    }

    /** Nothing outside the page changes its files, so there's nothing to watch for. */
    @InstanceMethod
    public static WatchService newWatchService(FileSystem self) throws IOException {
        throw new IOException("The browser's file system can't be watched");
    }
}
