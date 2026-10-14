package incari.classlib.substitutes.java.lang.management;

/** {@code java.lang.management.MemoryMXBean}, which TeaVM lacks (see {@link TManagementFactory}). */
public interface TMemoryMXBean {
    TMemoryUsage getHeapMemoryUsage();

    TMemoryUsage getNonHeapMemoryUsage();

    int getObjectPendingFinalizationCount();

    void gc();

    boolean isVerbose();

    void setVerbose(boolean value);
}
