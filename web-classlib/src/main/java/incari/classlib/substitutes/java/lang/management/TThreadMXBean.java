package incari.classlib.substitutes.java.lang.management;

/** {@code java.lang.management.ThreadMXBean}, which TeaVM lacks (see {@link TManagementFactory}). */
public interface TThreadMXBean {
    int getThreadCount();

    TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers);

    boolean isObjectMonitorUsageSupported();

    boolean isSynchronizerUsageSupported();

    boolean isThreadCpuTimeSupported();

    long getCurrentThreadCpuTime();
}
