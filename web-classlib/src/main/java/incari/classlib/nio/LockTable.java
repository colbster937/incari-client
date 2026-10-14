package incari.classlib.nio;

import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import incari.classlib.substitutes.java.nio.channels.TFileLock;
import incari.classlib.substitutes.java.nio.channels.TOverlappingFileLockException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The file locks this program holds, by file. The JDK refuses a lock that overlaps one already
 * held on the same file anywhere in the program, through any channel; with one page as the only
 * process, that refusal is all locking has left to do.
 */
final class LockTable {

    // Guarded by LockTable.class.
    private static final Map<Object, List<ChannelLock>> HELD = new HashMap<>();

    private LockTable() {}

    static synchronized TFileLock acquire(TFileChannel channel, Object key, long position, long size, boolean shared) {
        List<ChannelLock> held = HELD.get(key);
        if (held == null) {
            held = new ArrayList<>();
            HELD.put(key, held);
        }
        for (ChannelLock lock : held) {
            if (lock.overlaps(position, size)) {
                throw new TOverlappingFileLockException();
            }
        }
        ChannelLock lock = new ChannelLock(channel, key, position, size, shared);
        held.add(lock);
        return lock;
    }

    /** Releases every lock {@code channel} holds, as closing it does. */
    static synchronized void releaseAll(Object key, TFileChannel channel) {
        List<ChannelLock> held = HELD.get(key);
        if (held == null) {
            return;
        }
        held.removeIf(lock -> {
            if (!lock.channel().equals(channel)) {
                return false;
            }
            lock.valid = false;
            return true;
        });
        if (held.isEmpty()) {
            HELD.remove(key);
        }
    }

    private static synchronized void release(ChannelLock lock) {
        if (!lock.valid) {
            return;
        }
        lock.valid = false;
        List<ChannelLock> held = HELD.get(lock.key);
        if (held != null) {
            held.remove(lock);
            if (held.isEmpty()) {
                HELD.remove(lock.key);
            }
        }
    }

    private static final class ChannelLock extends TFileLock {

        private final Object key;
        // Written under LockTable's lock; read without it by isValid.
        volatile boolean valid = true;

        ChannelLock(TFileChannel channel, Object key, long position, long size, boolean shared) {
            super(channel, position, size, shared);
            this.key = key;
        }

        @Override
        public boolean isValid() {
            return valid && channel().isOpen();
        }

        @Override
        public void release() throws IOException {
            if (!channel().isOpen()) {
                throw new ClosedChannelException();
            }
            LockTable.release(this);
        }
    }
}
