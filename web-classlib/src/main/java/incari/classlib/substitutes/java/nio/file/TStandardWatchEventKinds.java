package incari.classlib.substitutes.java.nio.file;

import java.nio.file.Path;

/** {@code java.nio.file.StandardWatchEventKinds}, which TeaVM lacks (see {@link TWatchService}). */
public final class TStandardWatchEventKinds {

    public static final TWatchEvent.Kind<Object> OVERFLOW = new StandardKind<>("OVERFLOW", Object.class);
    public static final TWatchEvent.Kind<Path> ENTRY_CREATE = new StandardKind<>("ENTRY_CREATE", Path.class);
    public static final TWatchEvent.Kind<Path> ENTRY_DELETE = new StandardKind<>("ENTRY_DELETE", Path.class);
    public static final TWatchEvent.Kind<Path> ENTRY_MODIFY = new StandardKind<>("ENTRY_MODIFY", Path.class);

    private TStandardWatchEventKinds() {}

    private record StandardKind<T>(String name, Class<T> type) implements TWatchEvent.Kind<T> {
        @Override
        public String toString() {
            return name;
        }
    }
}
