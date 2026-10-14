import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Measures what handing a packet to the game's thread costs in the browser build (the lead in ADR
 * 0032's "Not done"). The game decodes each packet on the network's thread and calls its handler
 * there; the handler's first line ({@code PacketUtils.ensureRunningOnSameThread}) queues the packet
 * for the game's thread and throws a shared, stack-less {@code RunningOnDifferentThreadException},
 * which {@code Connection.channelRead0} catches. In the page's profiles that function's own time was
 * 2.5% of combat's samples, and the profile can't say whether it's the throw or the queueing.
 *
 * <p>Times, per packet, each part on its own, through three calls as in the game (the handoff's
 * check, the packet's {@code handle}, the connection's dispatch), the calls made through
 * interfaces with two implementations each so they stay calls: the call path alone; the thread
 * check; the queueing (an {@code ArrayDeque}, and a {@code ConcurrentLinkedQueue} as the game's
 * {@code PacketProcessor} has); the throw and catch; and all of it, as the game does it. Each also
 * through frames compiled as coroutines (most of the game's packet handlers are: a call that could
 * suspend, never made); from the stack's bottom and 30 calls up; on the page's main thread, in a
 * thread that slept first, and in one that never suspended. Prints the figures and "probe passed".
 *
 * <p>What it found (2026-10-03, Chrome on the desktop, development and release builds alike): the
 * throw and catch is most of the handoff, 65 to 100 ns on the page's main thread, and 640 to
 * 860 ns on any other thread, slept or not, against 5 to 15 ns for the calls and 20 to 30 ns for
 * either queue. The stack's depth doesn't matter. The game's network runs on another thread, so
 * every packet pays the dear throw.
 */
public final class PacketHandoffProbe {

    private static final int PACKETS = 50_000;
    private static final int ROUNDS = 5;

    /** As the game's exception: shared, no stack trace. */
    static final class Handoff extends RuntimeException {
        static final Handoff INSTANCE = new Handoff();

        private Handoff() {
            setStackTrace(new StackTraceElement[0]);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            setStackTrace(new StackTraceElement[0]);
            return this;
        }
    }

    record Queued(Object listener, Object packet) {}

    /**
     * What the handoff does: 0 nothing, 1 check, 2 queue (an ArrayDeque), 3 queue (a
     * ConcurrentLinkedQueue, as the game's PacketProcessor), 4 throw, 5 check + queue + throw as the
     * game (the concurrent queue).
     */
    static int mode;

    /** Never true; makes the coroutine variants' methods able to suspend. */
    static volatile boolean suspend;

    static final ArrayDeque<Queued> QUEUE = new ArrayDeque<>();
    static final ConcurrentLinkedQueue<Queued> CONCURRENT = new ConcurrentLinkedQueue<>();
    static Thread gameThread;
    static volatile long sink;

    interface Handler {
        void handle(Object packet);
    }

    interface Dispatch {
        void read(Handler handler, Object packet);
    }

    static void handoff(Object listener, Object packet) {
        switch (mode) {
            case 1 -> {
                if (Thread.currentThread() == gameThread) {
                    sink++;
                }
            }
            case 2 -> QUEUE.add(new Queued(listener, packet));
            case 3 -> CONCURRENT.add(new Queued(listener, packet));
            case 4 -> throw Handoff.INSTANCE;
            case 5 -> {
                if (Thread.currentThread() != gameThread) {
                    CONCURRENT.add(new Queued(listener, packet));
                    throw Handoff.INSTANCE;
                }
            }
            default -> {}
        }
    }

    static void maybeSuspend() {
        if (suspend) {
            Thread.yield();
        }
    }

    static final class PlainHandler implements Handler {
        @Override
        public void handle(Object packet) {
            handoff(this, packet);
            sink += packet.hashCode();
        }
    }

    static final class OtherHandler implements Handler {
        @Override
        public void handle(Object packet) {
            sink -= packet.hashCode();
        }
    }

    static final class CoroutineHandler implements Handler {
        @Override
        public void handle(Object packet) {
            maybeSuspend();
            handoff(this, packet);
            maybeSuspend();
            sink += packet.hashCode();
        }
    }

    static final class PlainDispatch implements Dispatch {
        @Override
        public void read(Handler handler, Object packet) {
            try {
                handler.handle(packet);
            } catch (Handoff e) {
                sink++;
            }
        }
    }

    static final class OtherDispatch implements Dispatch {
        @Override
        public void read(Handler handler, Object packet) {
            sink--;
        }
    }

    static final class CoroutineDispatch implements Dispatch {
        @Override
        public void read(Handler handler, Object packet) {
            maybeSuspend();
            try {
                handler.handle(packet);
            } catch (Handoff e) {
                sink++;
            }
            maybeSuspend();
        }
    }

    private PacketHandoffProbe() {}

    public static void main(String[] args) {
        // The game's thread is another: the check fails, as on the network's thread.
        gameThread = new Thread(() -> {});
        StringBuilder report = new StringBuilder("PacketHandoffProbe, ns a packet (best of " + ROUNDS + "):\n");
        // From the stack's bottom, and from deep in it, as the game's network pipeline is.
        for (int depth : new int[] {0, 30}) {
            report.append(deep(depth, depth));
        }
        // Depth -1: a thread that slept first; -2: a thread that never suspended.
        // And in a thread that has suspended and resumed, as the network's event loop has: it
        // waits for each message.
        StringBuilder resumed = new StringBuilder();
        Thread network = new Thread(() -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resumed.append(measure(-1));
        });
        network.start();
        try {
            network.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        report.append(resumed);
        // A thread that never suspended, to tell the thread from the suspension.
        StringBuilder fresh = new StringBuilder();
        Thread plain = new Thread(() -> fresh.append(measure(-2)));
        plain.start();
        try {
            plain.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        report.append(fresh);
        System.out.println(report);
        System.out.println("probe passed");
    }

    /** Measures {@code depth} calls down the stack. */
    static String deep(int remaining, int depth) {
        if (remaining > 0) {
            String result = deep(remaining - 1, depth);
            sink += result.length();
            return result;
        }
        return measure(depth);
    }

    static String measure(int depth) {
        Object packet = new Object();
        Handler[] handlers = {new PlainHandler(), new OtherHandler(), new CoroutineHandler()};
        Dispatch[] dispatches = {new PlainDispatch(), new OtherDispatch(), new CoroutineDispatch()};
        String[] modes = {"call path only", "thread check", "ArrayDeque add", "concurrent add", "throw and catch", "as the game (all)"};
        StringBuilder report = new StringBuilder();
        for (int variant = 0; variant < 2; variant++) {
            Handler handler = handlers[variant == 0 ? 0 : 2];
            Dispatch dispatch = dispatches[variant == 0 ? 0 : 2];
            // The other implementations, called once, so neither interface has a single target.
            dispatches[1].read(handlers[1], packet);
            handlers[1].handle(packet);
            for (int m = 0; m < modes.length; m++) {
                mode = m;
                double best = Double.MAX_VALUE;
                for (int round = 0; round < ROUNDS + 1; round++) {
                    QUEUE.clear();
                    CONCURRENT.clear();
                    long start = System.nanoTime();
                    for (int i = 0; i < PACKETS; i++) {
                        dispatch.read(handler, packet);
                        if ((i & 1023) == 0) {
                            QUEUE.clear();
                            // As the game's thread drains it each frame.
                            while (CONCURRENT.poll() != null) {}
                        }
                    }
                    double ns = (System.nanoTime() - start) / (double) PACKETS;
                    if (round > 0) {
                        best = Math.min(best, ns);
                    }
                }
                report.append(String.format(
                        "  depth %3d  %-10s %-18s %8.1f%n",
                        depth, variant == 0 ? "plain" : "coroutine", modes[m], best));
            }
        }
        return report.toString();
    }
}
