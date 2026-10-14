package incari.classlib.substitutes.java.lang;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.ThreadLocal}. Replaces TeaVM's, which keeps every thread's value but the main
 * thread's in a {@code WeakHashMap} keyed by thread: each {@code get()} on any other thread looked
 * its thread up through a weak reference, and on WebAssembly GC every weak reference read is a
 * call out to JavaScript ({@code WeakRef.deref}). The background pool's meshing reads the block tint
 * cache's thread-local and {@code IdleTimeExecutor}'s for every block, and Netty's event loop its
 * own for every message: about 8,000 calls out a second in a singleplayer world.
 *
 * <p>Here each thread's value lives in a {@link Slot} only that thread reads or writes. The slots
 * sit in a {@code WeakHashMap} by thread, so a finished thread's values can go, but a thread-local
 * also remembers the last thread that used it and that thread's slot, so a run of uses on one
 * thread (meshing a section, decoding a packet) compares one field. Only a switch of threads looks
 * in the map. The last thread stays reachable until another thread uses the thread-local.
 */
public class TThreadLocal<T extends @Nullable Object> {

    /** A slot's value before {@link #initialValue()} has run, or after {@link #remove()}. */
    private static final Object UNSET = new Object();

    /** One thread's value. Only that thread reads or writes it. */
    private static final class Slot {
        @Nullable
        Object value = UNSET;
    }

    /** The last thread to use this thread-local and its slot, replaced as one so threads see a pair. */
    private record Last(Thread thread, Slot slot) {}

    /** Every thread's slot. The slots never refer to their threads, so the keys can go. */
    private final Map<Thread, Slot> slots = new WeakHashMap<>();

    private volatile @Nullable Last last;

    public TThreadLocal() {}

    public static <S extends @Nullable Object> TThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new TThreadLocal<>() {
            @Override
            protected S initialValue() {
                return supplier.get();
            }
        };
    }

    protected @Nullable T initialValue() {
        return null;
    }

    // The marker is compared by identity, and the slot holds what set or initialValue gave, a T.
    @SuppressWarnings({"unchecked", "ReferenceEquality", "NullAway"})
    public T get() {
        Slot slot = slot();
        if (slot.value == UNSET) {
            // Outside any lock: the supplier may do anything, block included.
            slot.value = initialValue();
        }
        return (T) slot.value;
    }

    public void set(T value) {
        slot().value = value;
    }

    public void remove() {
        slot().value = UNSET;
    }

    /** The current thread's slot: the last one's if it's the same thread, else the map's. */
    @SuppressWarnings("ReferenceEquality") // the same thread, not an equal one
    private Slot slot() {
        Thread thread = Thread.currentThread();
        Last remembered = last;
        if (remembered != null && remembered.thread() == thread) {
            return remembered.slot();
        }
        Slot slot;
        synchronized (slots) {
            slot = slots.get(thread);
            if (slot == null) {
                slot = new Slot();
                slots.put(thread, slot);
            }
        }
        last = new Last(thread, slot);
        return slot;
    }
}
