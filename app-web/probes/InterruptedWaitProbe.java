import java.util.concurrent.locks.LockSupport;

/**
 * Regression probe for tools/teavm-patches' TObject fix: a thread interrupted in
 * {@code wait()} holds the monitor again when the InterruptedException arrives, so it leaves its
 * {@code synchronized} block normally. TeaVM threw without the monitor, and the block's exit threw
 * IllegalMonitorStateException: the game's sound thread, parked through web-classlib's
 * {@code LockSupport} and interrupted when the sound engine stops (joining a world), crashed the
 * game this way. Prints "probe passed" when both a plain wait and a park end cleanly.
 */
public final class InterruptedWaitProbe {

    private static final Object LOCK = new Object();
    private static volatile String waitResult = "not run";
    private static volatile String parkResult = "not run";

    private InterruptedWaitProbe() {}

    public static void main(String[] args) throws InterruptedException {
        Thread waiter = new Thread(() -> {
            try {
                synchronized (LOCK) {
                    try {
                        LOCK.wait();
                        waitResult = "woke without an interrupt";
                    } catch (InterruptedException e) {
                        waitResult = Thread.holdsLock(LOCK) ? "ok" : "interrupted without the monitor";
                    }
                }
            } catch (IllegalMonitorStateException e) {
                waitResult = "IllegalMonitorStateException leaving the block";
            }
        });
        Thread parker = new Thread(() -> {
            try {
                LockSupport.park();
                parkResult = Thread.currentThread().isInterrupted() ? "ok" : "returned without the interrupt";
            } catch (IllegalMonitorStateException e) {
                parkResult = "IllegalMonitorStateException in park";
            }
        });
        waiter.start();
        parker.start();
        Thread.sleep(50);
        waiter.interrupt();
        parker.interrupt();
        waiter.join();
        parker.join();
        boolean passed = waitResult.equals("ok") && parkResult.equals("ok");
        System.out.println(passed ? "probe passed" : "probe failed: wait " + waitResult + ", park " + parkResult);
    }
}
