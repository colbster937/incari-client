package incari.classlib.substitutes.java.lang.management;

/** {@code java.lang.management.GarbageCollectorMXBean}; the browser build reports none. */
public interface TGarbageCollectorMXBean {
    long getCollectionCount();

    long getCollectionTime();

    String getName();

    boolean isValid();

    String[] getMemoryPoolNames();
}
