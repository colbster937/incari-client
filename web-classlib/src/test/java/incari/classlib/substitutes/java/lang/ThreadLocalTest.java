package incari.classlib.substitutes.java.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThreadLocalTest {

    @Test
    void startsAtTheInitialValueOncePerThread() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        TThreadLocal<Integer> local = TThreadLocal.withInitial(made::incrementAndGet);
        assertThat(local.get()).isEqualTo(1);
        assertThat(local.get()).isEqualTo(1);
        int[] other = new int[1];
        Thread thread = new Thread(() -> other[0] = local.get());
        thread.start();
        thread.join();
        assertThat(other[0]).isEqualTo(2);
        assertThat(local.get()).isEqualTo(1);
    }

    @Test
    void defaultsToNull() {
        assertThat(new TThreadLocal<String>().get()).isNull();
    }

    @Test
    void keepsEachThreadsValueAcrossSwitches() throws InterruptedException {
        TThreadLocal<String> local = new TThreadLocal<>();
        local.set("main");
        String[] seen = new String[2];
        Thread thread = new Thread(() -> {
            seen[0] = local.get();
            local.set("other");
            seen[1] = local.get();
        });
        thread.start();
        thread.join();
        assertThat(seen).containsExactly(null, "other");
        // The other thread was the last to use it; the main thread's value comes back from the map.
        assertThat(local.get()).isEqualTo("main");
    }

    @Test
    void removeRunsTheInitialValueAgain() {
        AtomicInteger made = new AtomicInteger();
        TThreadLocal<Integer> local = TThreadLocal.withInitial(made::incrementAndGet);
        local.set(42);
        local.remove();
        assertThat(local.get()).isEqualTo(1);
    }

    @Test
    void holdsNull() {
        TThreadLocal<String> local = TThreadLocal.withInitial(() -> "initial");
        local.set(null);
        assertThat(local.get()).isNull();
    }

    @Test
    void anInitialValueMayUseOtherThreadLocals() {
        TThreadLocal<String> inner = TThreadLocal.withInitial(() -> "inner");
        TThreadLocal<String> outer = TThreadLocal.withInitial(() -> "outer of " + inner.get());
        assertThat(outer.get()).isEqualTo("outer of inner");
    }

    @Test
    void manyThreadsAtOnceEachSeeTheirOwn() throws InterruptedException {
        TThreadLocal<Integer> local = new TThreadLocal<>();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> started = new ArrayList<>();
        AtomicInteger wrong = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int id = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 20_000; n++) {
                    local.set(id * 1_000_000 + n);
                    if (local.get() != id * 1_000_000 + n) {
                        wrong.incrementAndGet();
                    }
                }
            });
            thread.start();
            started.add(thread);
        }
        start.countDown();
        for (Thread thread : started) {
            thread.join();
        }
        assertThat(wrong).hasValue(0);
    }
}
