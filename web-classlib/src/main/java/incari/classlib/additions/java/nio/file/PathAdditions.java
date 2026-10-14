package incari.classlib.additions.java.nio.file;

import incari.classlib.InstanceMethod;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/** {@code java.nio.file.Path} methods TeaVM lacks. */
public final class PathAdditions {

    private PathAdditions() {}

    @InstanceMethod
    public static Path resolve(Path self, String first, String... more) {
        Path result = self.resolve(first);
        for (String part : more) {
            result = result.resolve(part);
        }
        return result;
    }

    /** No watch service exists in the browser build to register with. */
    @InstanceMethod
    public static WatchKey register(Path self, WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new IOException("The browser's file system can't be watched");
    }
}
