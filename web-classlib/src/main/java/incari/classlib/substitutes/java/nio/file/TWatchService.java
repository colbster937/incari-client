package incari.classlib.substitutes.java.nio.file;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.WatchKey;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.nio.file.WatchService}, which TeaVM lacks. Nothing outside the page changes its files,
 * so the browser build never creates one ({@code FileSystem.newWatchService} throws).
 */
public interface TWatchService extends Closeable {
    @Override
    void close() throws IOException;

    @Nullable
    WatchKey poll();

    @Nullable
    WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException;

    WatchKey take() throws InterruptedException;
}
