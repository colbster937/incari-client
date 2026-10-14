package incari.classlib.substitutes.java.nio.file;

/** {@code java.nio.file.WatchEvent}, which TeaVM lacks (see {@link TWatchService}). */
public interface TWatchEvent<T> {
    Kind<T> kind();

    int count();

    T context();

    /** What happened to the watched entry. */
    interface Kind<T> {
        String name();

        Class<T> type();
    }

    /** How a registration is qualified. */
    interface Modifier {
        String name();
    }
}
