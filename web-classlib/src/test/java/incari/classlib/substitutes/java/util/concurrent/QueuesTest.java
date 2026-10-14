package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class QueuesTest {

    /** Moves {@code count} numbers through {@code queue} from one thread to another. */
    private static void producerConsumer(BlockingQueue<Integer> queue, int count) throws Exception {
        List<Integer> received = new ArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    received.add(queue.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        for (int i = 0; i < count; i++) {
            queue.put(i);
        }
        consumer.join();

        assertThat(received).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(received.get(i)).isEqualTo(i);
        }
    }

    @Test
    void linkedBlockingQueueProducerConsumer() throws Exception {
        producerConsumer(new TLinkedBlockingQueue<>(4), 1_000);
    }

    @Test
    void linkedBlockingDequeProducerConsumer() throws Exception {
        producerConsumer(new TLinkedBlockingDeque<>(4), 1_000);
    }

    @Test
    void synchronousQueueProducerConsumer() throws Exception {
        producerConsumer(new TSynchronousQueue<>(), 200);
    }

    @Test
    void boundedQueueTimesOutWhenFullAndEmpty() throws Exception {
        TLinkedBlockingQueue<String> queue = new TLinkedBlockingQueue<>(1);
        assertThat(queue.offer("a")).isTrue();
        assertThat(queue.offer("b")).isFalse();
        assertThat(queue.remainingCapacity()).isZero();
        assertThat(queue.offer("b", 20, TimeUnit.MILLISECONDS)).isFalse();

        assertThat(queue.poll(20, TimeUnit.MILLISECONDS)).isEqualTo("a");
        assertThat(queue.poll(20, TimeUnit.MILLISECONDS)).isNull();
        assertThatThrownBy(() -> new TLinkedBlockingQueue<>(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockedPutWakesWhenSpaceFrees() throws Exception {
        TLinkedBlockingQueue<Integer> queue = new TLinkedBlockingQueue<>(1);
        queue.put(1);
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        Thread.sleep(20);
        assertThat(queue.take()).isEqualTo(1);
        producer.join();
        assertThat(queue.take()).isEqualTo(2);
    }

    @Test
    void takeIsInterruptible() throws Exception {
        TLinkedBlockingQueue<Integer> queue = new TLinkedBlockingQueue<>();
        AtomicInteger interrupted = new AtomicInteger();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });
        consumer.start();
        Thread.sleep(20);
        consumer.interrupt();
        consumer.join();
        assertThat(interrupted).hasValue(1);
    }

    @Test
    void drainToAndIteratorRemove() {
        TLinkedBlockingQueue<Integer> queue = new TLinkedBlockingQueue<>(List.of(1, 2, 3, 4, 5));
        List<Integer> drained = new ArrayList<>();

        assertThat(queue.drainTo(drained, 2)).isEqualTo(2);
        assertThat(drained).containsExactly(1, 2);

        for (Iterator<Integer> it = queue.iterator(); it.hasNext(); ) {
            if (it.next() == 4) {
                it.remove();
            }
        }
        assertThat(queue).containsExactly(3, 5);
        assertThat(queue.drainTo(drained)).isEqualTo(2);
        assertThat(queue).isEmpty();
        assertThatThrownBy(() -> queue.drainTo(queue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockingDequeWorksFromBothEnds() throws Exception {
        TLinkedBlockingDeque<String> deque = new TLinkedBlockingDeque<>(3);
        deque.putFirst("b");
        deque.putFirst("a");
        deque.putLast("c");
        assertThatThrownBy(() -> deque.addLast("d")).isInstanceOf(IllegalStateException.class);
        assertThat(deque.offerLast("d", 10, TimeUnit.MILLISECONDS)).isFalse();

        List<String> descending = new ArrayList<>();
        deque.descendingIterator().forEachRemaining(descending::add);
        assertThat(descending).containsExactly("c", "b", "a");

        assertThat(deque.takeLast()).isEqualTo("c");
        assertThat(deque.pollFirst(10, TimeUnit.MILLISECONDS)).isEqualTo("a");
        assertThat(deque.pop()).isEqualTo("b");
        assertThat(deque.pollLast(10, TimeUnit.MILLISECONDS)).isNull();
        assertThatThrownBy(deque::removeFirst).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void priorityBlockingQueueOrdersAndBlocks() throws Exception {
        TPriorityBlockingQueue<Integer> queue = new TPriorityBlockingQueue<>(4, Comparator.reverseOrder());
        queue.addAll(List.of(3, 1, 4, 1, 5));

        assertThat(queue.take()).isEqualTo(5);
        assertThat(queue.poll()).isEqualTo(4);
        assertThat(queue.size()).isEqualTo(3);

        TPriorityBlockingQueue<Integer> empty = new TPriorityBlockingQueue<>();
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            empty.put(9);
        });
        producer.start();
        assertThat(empty.poll(5, TimeUnit.SECONDS)).isEqualTo(9);
        producer.join();
    }

    @Test
    void synchronousQueueOnlyHandsOverToWaitingThreads() throws Exception {
        TSynchronousQueue<String> queue = new TSynchronousQueue<>();
        assertThat(queue.offer("nobody waiting")).isFalse();
        assertThat(queue.poll()).isNull();
        assertThat(queue.offer("x", 10, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queue.poll(10, TimeUnit.MILLISECONDS)).isNull();
        assertThat(queue).isEmpty();

        String[] got = new String[1];
        Thread taker = new Thread(() -> {
            try {
                got[0] = queue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        taker.start();
        assertThat(queue.offer("handed", 5, TimeUnit.SECONDS)).isTrue();
        taker.join();
        assertThat(got[0]).isEqualTo("handed");
    }

    /** A delayed element due at a fixed nanoTime. */
    private record Due(String name, long at) implements TDelayed {
        static Due in(String name, long millis) {
            return new Due(name, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(TDelayed o) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    @Test
    void delayQueueReleasesElementsWhenDue() throws Exception {
        TDelayQueue<Due> queue = new TDelayQueue<>();
        queue.add(Due.in("later", 60));
        queue.add(Due.in("sooner", 20));

        assertThat(queue.poll()).isNull();
        assertThat(queue.peek()).isNotNull();
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.take().name()).isEqualTo("sooner");
        assertThat(queue.poll(5, TimeUnit.SECONDS).name()).isEqualTo("later");
        assertThat(queue.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @SuppressWarnings("ModifyCollectionInEnhancedForLoop") // weakly consistent iteration is under test
    void concurrentLinkedQueueAndDeque() {
        TConcurrentLinkedQueue<String> queue = new TConcurrentLinkedQueue<>();
        queue.add("a");
        queue.offer("b");
        queue.addAll(List.of("c", "d"));
        for (String s : queue) {
            if (s.equals("b")) {
                queue.remove(s); // no ConcurrentModificationException
            }
        }
        assertThat(queue.poll()).isEqualTo("a");
        assertThat(queue).containsExactly("c", "d");
        assertThatThrownBy(() -> queue.add(null)).isInstanceOf(NullPointerException.class);

        TConcurrentLinkedDeque<String> deque = new TConcurrentLinkedDeque<>();
        deque.push("b");
        deque.push("a");
        deque.addLast("c");
        assertThat(deque.peekLast()).isEqualTo("c");
        assertThat(deque.pollFirst()).isEqualTo("a");
        assertThat(deque.removeLast()).isEqualTo("c");
        assertThat(deque).containsExactly("b");
    }

    @Test
    void concurrentLinkedQueueAcrossThreads() throws Exception {
        TConcurrentLinkedQueue<Integer> queue = new TConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 1_000; i++) {
                    queue.add(i);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(queue).hasSize(4_000);
    }

    @Test
    @SuppressWarnings("ModifyCollectionInEnhancedForLoop") // weakly consistent iteration is under test
    void copyOnWriteArraySetKeepsElementsUnique() {
        TCopyOnWriteArraySet<String> set = new TCopyOnWriteArraySet<>(List.of("a", "b", "a"));
        assertThat(set).containsExactly("a", "b");
        assertThat(set.add("a")).isFalse();
        assertThat(set.addAll(List.of("b", "c"))).isTrue();
        for (String s : set) {
            set.remove(s); // iterating a snapshot
        }
        assertThat(set).isEmpty();

        set.addAll(List.of("x", "yy", "zzz"));
        assertThat(set.removeIf(s -> s.length() > 1)).isTrue();
        assertThat(set).containsExactly("x");
    }
}
