package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.SynchronousQueue}: no capacity, each insert hands its element
 * straight to a waiting taker. Waiting producers and consumers are kept in FIFO lists of nodes
 * behind the queue's monitor, and each one {@code wait}s until its node is matched. Both the fair
 * and the default mode match in FIFO order.
 */
public class TSynchronousQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    /** Producers waiting for a taker, each holding its item. */
    private final ArrayDeque<Node> producers = new ArrayDeque<>();
    /** Consumers waiting for an item. */
    private final ArrayDeque<Node> consumers = new ArrayDeque<>();

    public TSynchronousQueue() {
        this(false);
    }

    /** Matching is FIFO whatever {@code fair} says. */
    public TSynchronousQueue(boolean fair) {}

    /** One waiting producer or consumer. Compared by identity. */
    private static final class Node {
        @Nullable
        Object item;

        boolean matched;

        Node(@Nullable Object item) {
            this.item = item;
        }
    }

    /**
     * Hands {@code e} to a consumer already waiting, if there is one; never waits. Kept apart from
     * the waiting path so that {@link #offer(Object)} can't suspend: in the browser build, a method
     * that might wait makes every caller of {@code Queue.offer} a coroutine.
     */
    private boolean handToWaitingConsumer(E e) {
        synchronized (this) {
            Node consumer = consumers.pollFirst();
            if (consumer == null) {
                return false;
            }
            consumer.item = e;
            consumer.matched = true;
            notifyAll();
            return true;
        }
    }

    /** Takes the item of a producer already waiting, if there is one; never waits. */
    private @Nullable Node takeFromWaitingProducer() {
        synchronized (this) {
            Node producer = producers.pollFirst();
            if (producer == null) {
                return null;
            }
            producer.matched = true;
            notifyAll();
            return producer;
        }
    }

    /**
     * Hands {@code e} to a consumer: at once if one is waiting, else waiting for one, forever if
     * not {@code timed}, else up to {@code nanos}. Returns whether a consumer took it.
     */
    private boolean transferOut(E e, boolean timed, long nanos) throws InterruptedException {
        synchronized (this) {
            if (handToWaitingConsumer(e)) {
                return true;
            }
            if (timed && nanos <= 0) {
                return false;
            }
            Node node = new Node(e);
            producers.addLast(node);
            awaitMatch(node, timed, nanos, producers);
            return node.matched;
        }
    }

    /** Takes an item from a producer, waiting as {@link #transferOut} does; null if none came. */
    private @Nullable E transferIn(boolean timed, long nanos) throws InterruptedException {
        synchronized (this) {
            Node producer = takeFromWaitingProducer();
            if (producer != null) {
                return Monitors.cast(producer.item);
            }
            if (timed && nanos <= 0) {
                return null;
            }
            Node node = new Node(null);
            consumers.addLast(node);
            awaitMatch(node, timed, nanos, consumers);
            return node.matched ? Monitors.cast(node.item) : null;
        }
    }

    /**
     * Caller holds this monitor and has queued {@code node} in {@code waiting}. Waits until the node
     * is matched or the time runs out, and takes it out of the list if it wasn't matched. An
     * interrupt after the match is kept as the flag; one before it cancels the node and is thrown.
     */
    private void awaitMatch(Node node, boolean timed, long nanos, ArrayDeque<Node> waiting)
            throws InterruptedException {
        try {
            while (!node.matched) {
                if (!timed) {
                    wait();
                } else if (nanos > 0) {
                    nanos = Monitors.awaitNanos(this, nanos);
                } else {
                    break;
                }
            }
        } catch (InterruptedException ie) {
            if (node.matched) {
                Thread.currentThread().interrupt();
                return;
            }
            waiting.remove(node);
            throw ie;
        }
        if (!node.matched) {
            waiting.remove(node);
        }
    }

    @Override
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        Monitors.checkInterrupt();
        transferOut(e, false, 0L);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        Monitors.checkInterrupt();
        return transferOut(e, true, unit.toNanos(timeout));
    }

    /** Succeeds only if a taker is already waiting. */
    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        return handToWaitingConsumer(e);
    }

    @Override
    public E take() throws InterruptedException {
        Monitors.checkInterrupt();
        return Objects.requireNonNull(transferIn(false, 0L));
    }

    @Override
    public @Nullable E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Monitors.checkInterrupt();
        return transferIn(true, unit.toNanos(timeout));
    }

    /** Succeeds only if a producer is already waiting. */
    @Override
    public @Nullable E poll() {
        Node producer = takeFromWaitingProducer();
        return producer == null ? null : Monitors.cast(producer.item);
    }

    // A synchronous queue never holds anything: the collection views are all empty.

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public int remainingCapacity() {
        return 0;
    }

    @Override
    public void clear() {}

    @Override
    public boolean contains(@Nullable Object o) {
        return false;
    }

    @Override
    public boolean remove(@Nullable Object o) {
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public @Nullable E peek() {
        return null;
    }

    @Override
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @Override
    @SuppressWarnings("NullAway") // Collection.toArray: a null marks the end of the (empty) contents
    public <T> T[] toArray(T[] a) {
        if (a.length > 0) {
            a[0] = null;
        }
        return a;
    }

    @Override
    public String toString() {
        return "[]";
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @SuppressWarnings("ReferenceEquality") // the contract is about this very collection
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int n = 0;
        E e;
        while (n < maxElements && (e = poll()) != null) {
            c.add(e);
            n++;
        }
        return n;
    }
}
