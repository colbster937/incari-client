/**
 * Checks the throw census (`-Pteavm.throwCensus`, ADR 0033): throws a known number of times on the
 * main thread and on another, across one census report, then prints "probe passed". Run with the
 * census on and read the "[Incari] Throws" lines: the main thread's site counts 1000 on it, the
 * other thread's 3000 off it.
 */
public final class ThrowCensusProbe {

    static int caught;

    private ThrowCensusProbe() {}

    static void fail(int i) {
        throw new IllegalStateException("throw " + i);
    }

    static void failElsewhere(int i) {
        throw new UnsupportedOperationException("throw " + i);
    }

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            try {
                fail(i);
            } catch (IllegalStateException e) {
                caught++;
            }
        }
        Thread other = new Thread(() -> {
            for (int i = 0; i < 3000; i++) {
                try {
                    failElsewhere(i);
                } catch (UnsupportedOperationException e) {
                    caught++;
                }
            }
        });
        other.start();
        other.join();
        // The census reports at the first throw ten seconds after its first.
        Thread.sleep(10_500);
        try {
            fail(-1);
        } catch (IllegalStateException e) {
            caught++;
        }
        System.out.println(caught == 4001 ? "probe passed" : "probe failed: caught " + caught);
    }
}
