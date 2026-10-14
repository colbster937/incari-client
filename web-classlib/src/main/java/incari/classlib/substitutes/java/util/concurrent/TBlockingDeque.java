package incari.classlib.substitutes.java.util.concurrent;

import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.BlockingDeque}, which TeaVM lacks. Implemented here by
 * {@link TLinkedBlockingDeque}.
 */
public interface TBlockingDeque<E> extends BlockingQueue<E>, Deque<E> {

    @Override
    void addFirst(E e);

    @Override
    void addLast(E e);

    @Override
    boolean offerFirst(E e);

    @Override
    boolean offerLast(E e);

    void putFirst(E e) throws InterruptedException;

    void putLast(E e) throws InterruptedException;

    boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException;

    boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException;

    E takeFirst() throws InterruptedException;

    E takeLast() throws InterruptedException;

    @Nullable
    E pollFirst(long timeout, TimeUnit unit) throws InterruptedException;

    @Nullable
    E pollLast(long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    boolean removeFirstOccurrence(Object o);

    @Override
    boolean removeLastOccurrence(Object o);

    @Override
    boolean add(E e);

    @Override
    boolean offer(E e);

    @Override
    void put(E e) throws InterruptedException;

    @Override
    boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    E remove();

    @Override
    @Nullable
    E poll();

    @Override
    E take() throws InterruptedException;

    @Override
    @Nullable
    E poll(long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    E element();

    @Override
    @Nullable
    E peek();

    @Override
    boolean remove(Object o);

    @Override
    boolean contains(Object o);

    @Override
    int size();

    @Override
    Iterator<E> iterator();

    @Override
    void push(E e);
}
