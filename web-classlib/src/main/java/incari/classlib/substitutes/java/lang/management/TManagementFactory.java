package incari.classlib.substitutes.java.lang.management;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code java.lang.management.ManagementFactory}, which TeaVM lacks. The game reads memory, uptime
 * and thread numbers for its debug screen and crash reports; a page reports what the browser build
 * knows (TeaVM's memory figures) and zeros or nothing for the rest.
 */
public final class TManagementFactory {

    private static final long START = System.currentTimeMillis();

    private TManagementFactory() {}

    public static TMemoryMXBean getMemoryMXBean() {
        return Memory.INSTANCE;
    }

    public static TRuntimeMXBean getRuntimeMXBean() {
        return RuntimeBean.INSTANCE;
    }

    public static TThreadMXBean getThreadMXBean() {
        return Threads.INSTANCE;
    }

    public static List<TGarbageCollectorMXBean> getGarbageCollectorMXBeans() {
        return List.of();
    }

    public static javax.management.MBeanServer getPlatformMBeanServer() {
        throw new UnsupportedOperationException("The browser build has no MBean server");
    }

    private static final class Memory implements TMemoryMXBean {
        static final Memory INSTANCE = new Memory();

        @Override
        public TMemoryUsage getHeapMemoryUsage() {
            Runtime runtime = Runtime.getRuntime();
            long total = runtime.totalMemory();
            return new TMemoryUsage(-1, total - runtime.freeMemory(), total, total);
        }

        @Override
        public TMemoryUsage getNonHeapMemoryUsage() {
            return new TMemoryUsage(-1, 0, 0, -1);
        }

        @Override
        public int getObjectPendingFinalizationCount() {
            return 0;
        }

        @Override
        public void gc() {
            System.gc();
        }

        @Override
        public boolean isVerbose() {
            return false;
        }

        @Override
        public void setVerbose(boolean value) {}
    }

    private static final class RuntimeBean implements TRuntimeMXBean {
        static final RuntimeBean INSTANCE = new RuntimeBean();

        @Override
        public long getPid() {
            return 0;
        }

        @Override
        public String getName() {
            return "browser";
        }

        @Override
        public String getVmName() {
            return "TeaVM";
        }

        @Override
        public String getVmVendor() {
            return "TeaVM";
        }

        @Override
        public String getVmVersion() {
            return "";
        }

        @Override
        public String getSpecName() {
            return "Java Virtual Machine Specification";
        }

        @Override
        public String getSpecVendor() {
            return "";
        }

        @Override
        public String getSpecVersion() {
            return "";
        }

        @Override
        public List<String> getInputArguments() {
            return List.of();
        }

        @Override
        public long getUptime() {
            return System.currentTimeMillis() - START;
        }

        @Override
        public long getStartTime() {
            return START;
        }

        @Override
        public Map<String, String> getSystemProperties() {
            return Collections.emptyMap();
        }
    }

    private static final class Threads implements TThreadMXBean {
        static final Threads INSTANCE = new Threads();

        @Override
        public int getThreadCount() {
            return 1;
        }

        @Override
        public TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) {
            return new TThreadInfo[0];
        }

        @Override
        public boolean isObjectMonitorUsageSupported() {
            return false;
        }

        @Override
        public boolean isSynchronizerUsageSupported() {
            return false;
        }

        @Override
        public boolean isThreadCpuTimeSupported() {
            return false;
        }

        @Override
        public long getCurrentThreadCpuTime() {
            return -1;
        }
    }
}
