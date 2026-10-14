package incari.classlib.substitutes.java.nio.file;

import java.nio.file.WatchEvent;
import java.nio.file.Watchable;
import java.util.List;

/** {@code java.nio.file.WatchKey}, which TeaVM lacks (see {@link TWatchService}). */
public interface TWatchKey {
    boolean isValid();

    List<WatchEvent<?>> pollEvents();

    boolean reset();

    void cancel();

    Watchable watchable();
}
