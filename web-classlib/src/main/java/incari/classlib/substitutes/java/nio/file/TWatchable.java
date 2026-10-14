package incari.classlib.substitutes.java.nio.file;

import java.io.IOException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/** {@code java.nio.file.Watchable}, which TeaVM lacks (see {@link TWatchService}). */
public interface TWatchable {
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
            throws IOException;

    WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException;
}
