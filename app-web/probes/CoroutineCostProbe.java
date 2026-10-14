/**
 * Measures what TeaVM's coroutine transformation costs ordinary code in the browser build: the same
 * workload compiled twice, once as plain functions and once as coroutines, timed in one module.
 *
 * <p>The workload looks like the game's hot paths: a loop over objects calling through interfaces
 * with several implementations (as block, shape and entity code does), and a small open-addressing
 * table whose lookups call the keys' {@code hashCode} and {@code equals} (as every {@code Map.get}
 * does). In the game those virtual calls are suspension points because one implementation somewhere
 * can block (ADR 0027), so the coroutine copy puts one blocking implementation into each call site's
 * reach, never called. Everything else is the same code, duplicated by hand: shared code would merge
 * the two copies in TeaVM's flow analysis. Prints nanoseconds per item for each copy, and "probe
 * passed".
 *
 * <p>Each copy has one extra implementation at each call that the loops never call: blocking in
 * the coroutine copy, harmless in the plain one, so both call sites are equally polymorphic.
 *
 * <p>What it found (2026-10-05, release build, Chrome on the desktop): with coroutines the
 * coroutine copy takes 8.3 to 8.7 ns an item against 5.5 to 5.9 ns, about 1.5 times as long; in the
 * Fast build ({@code -Pteavm.fast}, no coroutines) the two take the same, 5.3 to 5.7 ns. Only the
 * callers ({@code coroutineWork}, {@code CTable.get}) are coroutines here; the shapes and keys stay
 * plain.
 * That's about 0.7 ns for each call through a suspension point, from the coroutine's prologue, the
 * check after each call, and locals kept alive and nullable for the save path.
 */
public final class CoroutineCostProbe {

    private static final int ITEMS = 200_000;
    private static final int ROUNDS = 7;

    /** Never true; lets the coroutine copy's blocking implementations suspend. */
    static volatile boolean suspend;

    static volatile long sink;

    static void maybeSleep() {
        if (suspend) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ----- Plain copy -----

    interface PShape {
        double value(int i, PVec v);
    }

    static final class PVec {
        double x;
        double y;
        double z;

        PVec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class PBox implements PShape {
        final double size;

        PBox(double size) {
            this.size = size;
        }

        @Override
        public double value(int i, PVec v) {
            return v.x * size + v.y - (i & 3);
        }
    }

    static final class PSlab implements PShape {
        @Override
        public double value(int i, PVec v) {
            return v.z * 0.5 + (i & 7);
        }
    }

    static final class PStair implements PShape {
        final PShape inner;

        PStair(PShape inner) {
            this.inner = inner;
        }

        @Override
        public double value(int i, PVec v) {
            return inner.value(i + 1, v) * 0.25 + v.y;
        }
    }

    static class PKey {
        final int a;
        final int b;

        PKey(int a, int b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int hashCode() {
            return a * 31 + b;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PKey k && k.a == a && k.b == b;
        }
    }

    /** As CBlocking, without the blocking: the same number of implementations at each call. */
    static final class PExtra implements PShape {
        @Override
        public double value(int i, PVec v) {
            sink++;
            return 0;
        }
    }

    /** As CBlockingKey, without the blocking. */
    static final class PExtraKey extends PKey {
        PExtraKey() {
            super(-1, -1);
        }

        @Override
        public int hashCode() {
            sink++;
            return 7;
        }

        @Override
        public boolean equals(Object o) {
            sink++;
            return false;
        }
    }

    static final class PKey2 extends PKey {
        PKey2(int a, int b) {
            super(a, b);
        }

        @Override
        public int hashCode() {
            return a * 17 ^ b;
        }
    }

    static final class PTable {
        final Object[] keys = new Object[1024];
        final int[] values = new int[1024];

        void put(Object key, int value) {
            int slot = key.hashCode() & 1023;
            while (keys[slot] != null && !keys[slot].equals(key)) {
                slot = (slot + 1) & 1023;
            }
            keys[slot] = key;
            values[slot] = value;
        }

        int get(Object key) {
            int slot = key.hashCode() & 1023;
            while (keys[slot] != null) {
                if (keys[slot].equals(key)) {
                    return values[slot];
                }
                slot = (slot + 1) & 1023;
            }
            return -1;
        }
    }

    static double plainWork(PShape[] shapes, PVec[] vecs, PTable table, Object[] keys) {
        double acc = 0;
        for (int i = 0; i < ITEMS; i++) {
            PVec v = vecs[i & 63];
            acc += shapes[i & 7].value(i, v);
            acc += table.get(keys[i & 255]);
        }
        return acc;
    }

    // ----- Coroutine copy: the same, with one blocking implementation in reach -----

    interface CShape {
        double value(int i, CVec v);
    }

    static final class CVec {
        double x;
        double y;
        double z;

        CVec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class CBox implements CShape {
        final double size;

        CBox(double size) {
            this.size = size;
        }

        @Override
        public double value(int i, CVec v) {
            return v.x * size + v.y - (i & 3);
        }
    }

    static final class CSlab implements CShape {
        @Override
        public double value(int i, CVec v) {
            return v.z * 0.5 + (i & 7);
        }
    }

    static final class CStair implements CShape {
        final CShape inner;

        CStair(CShape inner) {
            this.inner = inner;
        }

        @Override
        public double value(int i, CVec v) {
            return inner.value(i + 1, v) * 0.25 + v.y;
        }
    }

    /** Never in the timed loop's array slots it reads; only makes the calls suspension points. */
    static final class CBlocking implements CShape {
        @Override
        public double value(int i, CVec v) {
            maybeSleep();
            return 0;
        }
    }

    static class CKey {
        final int a;
        final int b;

        CKey(int a, int b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int hashCode() {
            return a * 31 + b;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CKey k && k.a == a && k.b == b;
        }
    }

    static final class CKey2 extends CKey {
        CKey2(int a, int b) {
            super(a, b);
        }

        @Override
        public int hashCode() {
            return a * 17 ^ b;
        }
    }

    /** As the game's one blocking toString makes every Object method a suspension point. */
    static final class CBlockingKey extends CKey {
        CBlockingKey() {
            super(-1, -1);
        }

        @Override
        public int hashCode() {
            maybeSleep();
            return 7;
        }

        @Override
        public boolean equals(Object o) {
            maybeSleep();
            return false;
        }
    }

    static final class CTable {
        final Object[] keys = new Object[1024];
        final int[] values = new int[1024];

        void put(Object key, int value) {
            int slot = key.hashCode() & 1023;
            while (keys[slot] != null && !keys[slot].equals(key)) {
                slot = (slot + 1) & 1023;
            }
            keys[slot] = key;
            values[slot] = value;
        }

        int get(Object key) {
            int slot = key.hashCode() & 1023;
            while (keys[slot] != null) {
                if (keys[slot].equals(key)) {
                    return values[slot];
                }
                slot = (slot + 1) & 1023;
            }
            return -1;
        }
    }

    static double coroutineWork(CShape[] shapes, CVec[] vecs, CTable table, Object[] keys) {
        double acc = 0;
        for (int i = 0; i < ITEMS; i++) {
            CVec v = vecs[i & 63];
            acc += shapes[i & 7].value(i, v);
            acc += table.get(keys[i & 255]);
        }
        return acc;
    }

    public static void main(String[] args) throws InterruptedException {
        // Threads must exist for TeaVM to compile anything as a coroutine.
        Thread other = new Thread(() -> sink++);
        other.start();
        other.join();

        PShape[] pShapes = new PShape[9];
        CShape[] cShapes = new CShape[9];
        for (int i = 0; i < 8; i++) {
            pShapes[i] = switch (i % 3) {
                case 0 -> new PBox(i);
                case 1 -> new PSlab();
                default -> new PStair(new PBox(i + 1));
            };
            cShapes[i] = switch (i % 3) {
                case 0 -> new CBox(i);
                case 1 -> new CSlab();
                default -> new CStair(new CBox(i + 1));
            };
        }
        cShapes[8] = new CBlocking();
        pShapes[8] = new PExtra();
        PVec[] pVecs = new PVec[64];
        CVec[] cVecs = new CVec[64];
        for (int i = 0; i < 64; i++) {
            pVecs[i] = new PVec(i, i * 0.5, i * 0.25);
            cVecs[i] = new CVec(i, i * 0.5, i * 0.25);
        }
        PTable pTable = new PTable();
        CTable cTable = new CTable();
        Object[] pKeys = new Object[257];
        Object[] cKeys = new Object[257];
        for (int i = 0; i < 256; i++) {
            pKeys[i] = (i & 1) == 0 ? new PKey(i, i >> 2) : new PKey2(i, i >> 2);
            cKeys[i] = (i & 1) == 0 ? new CKey(i, i >> 2) : new CKey2(i, i >> 2);
            pTable.put(pKeys[i], i);
            cTable.put(cKeys[i], i);
        }
        cKeys[256] = new CBlockingKey();
        pKeys[256] = new PExtraKey();
        // The blocking ones reach the same call sites, as in the game, but are never called in the
        // timed loops (they index & 7 and & 255).
        if (suspend) {
            cTable.put(cKeys[256], 0);
            sink += (long) cShapes[8].value(0, cVecs[0]);
            pTable.put(pKeys[256], 0);
            sink += (long) pShapes[8].value(0, pVecs[0]);
        }

        for (int round = 0; round < ROUNDS; round++) {
            // Alternate which copy runs first, so neither gains from going second.
            double a;
            double b;
            long plain;
            long coroutine;
            if ((round & 1) == 0) {
                long t0 = System.nanoTime();
                a = plainWork(pShapes, pVecs, pTable, pKeys);
                long t1 = System.nanoTime();
                b = coroutineWork(cShapes, cVecs, cTable, cKeys);
                plain = t1 - t0;
                coroutine = System.nanoTime() - t1;
            } else {
                long t0 = System.nanoTime();
                b = coroutineWork(cShapes, cVecs, cTable, cKeys);
                long t1 = System.nanoTime();
                a = plainWork(pShapes, pVecs, pTable, pKeys);
                coroutine = t1 - t0;
                plain = System.nanoTime() - t1;
            }
            sink += (long) (a + b);
            System.out.println("round " + round + ": plain " + String.format("%.2f", plain / (double) ITEMS)
                    + " ns/item, coroutine " + String.format("%.2f", coroutine / (double) ITEMS)
                    + " ns/item, ratio " + String.format("%.2f", coroutine / (double) plain)
                    + (a == b ? "" : " (results differ!)"));
        }
        System.out.println("probe passed");
    }
}
