package incari.classlib.substitutes.java.lang.management;

/** {@code java.lang.management.MemoryUsage}, which TeaVM lacks. Sizes in bytes; -1 is unknown. */
public class TMemoryUsage {

    private final long init;
    private final long used;
    private final long committed;
    private final long max;

    public TMemoryUsage(long init, long used, long committed, long max) {
        this.init = init;
        this.used = used;
        this.committed = committed;
        this.max = max;
    }

    public long getInit() {
        return init;
    }

    public long getUsed() {
        return used;
    }

    public long getCommitted() {
        return committed;
    }

    public long getMax() {
        return max;
    }

    @Override
    public String toString() {
        return "init = " + init + "(" + (init >> 10) + "K) used = " + used + "(" + (used >> 10) + "K) committed = "
                + committed + "(" + (committed >> 10) + "K) max = " + max + "(" + (max >> 10) + "K)";
    }
}
