import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * References polled from a ReferenceQueue keep their own class's methods. TeaVM's analysis took
 * everything poll() returns for a plain WeakReference, so a method only ever called on polled
 * references (Guava's WeakValueReference.getEntry, when a weak-value map reclaims an entry) had
 * no code in the subclass, and the call trapped ("dereferencing a null pointer"). Makes garbage
 * until the browser collects some referents and the queue hands their references back.
 */
public class WeakQueueProbe {

    /** Called through an interface, as Guava calls WeakValueReference.getEntry(). */
    interface HasTag {
        int tag();
    }

    static class Tagged extends WeakReference<Object> implements HasTag {
        private final int tag;

        Tagged(Object referent, ReferenceQueue<Object> queue, int tag) {
            super(referent, queue);
            this.tag = tag;
        }

        /** Called only on references that came back from the queue. */
        @Override
        public int tag() {
            return tag;
        }
    }

    public static void main(String[] args) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Tagged[] kept = new Tagged[20_000];
        for (int i = 0; i < kept.length; i++) {
            kept[i] = new Tagged(new int[64], queue, i + 1);
        }
        long sum = 0;
        int polled = 0;
        Object[] churn = new Object[1024];
        for (int round = 0; round < 400 && polled == 0; round++) {
            for (int i = 0; i < 200_000; i++) {
                churn[i & 1023] = new int[32];
            }
            // Lets the page's event loop run, where the finalization callbacks come.
            Thread.sleep(20);
            Reference<?> reference;
            while ((reference = queue.poll()) != null) {
                sum += ((HasTag) reference).tag();
                polled++;
            }
        }
        System.out.println("polled " + polled + " references, tags summing to " + sum);
        System.out.println(polled > 0 && sum > 0 ? "probe passed" : "probe failed: nothing came back from the queue");
    }
}
