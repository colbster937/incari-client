package incari.platform.desktop;

import incari.platform.api.profile.FrameSample;
import incari.platform.api.profile.MemoryUsage;
import incari.platform.api.profile.PlatformProfiling;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Profiling on the JVM: the heap and the collections from the JVM's management beans, the
 * direct buffers as the memory outside the heap. Frames aren't kept anywhere: on the desktop the
 * profiler overlay and the JVM's own tools read them.
 */
final class DesktopProfiling implements PlatformProfiling {

    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

    @Override
    public MemoryUsage memory() {
        Runtime runtime = Runtime.getRuntime();
        long collections = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            collections += Math.max(0, collector.getCollectionCount());
        }
        long direct = 0;
        for (BufferPoolMXBean pool : bufferPools) {
            direct += Math.max(0, pool.getMemoryUsed());
        }
        return new MemoryUsage(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), direct, collections);
    }

    @Override
    public void frameProfiled(FrameSample sample) {}

    @Override
    public void mark(String name) {}
}
