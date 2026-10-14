package incari.client.ui;

import net.minecraft.util.Util;

/**
 * Eased values for the skin's animations in Normal: how far a button has lit up toward its hover
 * state, and when the hover began (the light sweep runs from then). Each is kept for a control by
 * its rectangle, since the sprite the skin is asked to draw doesn't say which widget it's for, and
 * widgets stay put between layouts. In Potato every value is its target at once.
 *
 * <p>Render thread. Hot path: fixed arrays, nothing allocates.
 */
final class Motion {

    /** How many controls are animated at once; the least recently drawn gives up its slot. */
    private static final int SLOTS = 64;

    /** How long a hover takes to ease in or out, roughly: the time constant, in milliseconds. */
    private static final float EASE_MILLIS = 70.0F;

    private static final long[] keys = new long[SLOTS];
    private static final float[] values = new float[SLOTS];
    private static final long[] updated = new long[SLOTS];
    private static final long[] since = new long[SLOTS];
    private static final boolean[] used = new boolean[SLOTS];

    private static int slot;

    private Motion() {}

    /** A key for the control in this rectangle. */
    static long key(int x, int y, int width, int height) {
        return ((long) (x & 0xFFFF) << 48)
                | ((long) (y & 0xFFFF) << 32)
                | ((long) (width & 0xFFFF) << 16)
                | (height & 0xFFFF);
    }

    /**
     * Moves the control's value toward {@code target} (0 or 1) by the time since it was last
     * drawn, and returns it. Call {@link #since} next for when it last turned on.
     */
    static float ease(long key, float target) {
        if (!Look.normal()) {
            return target;
        }
        long now = Util.getMillis();
        int found = find(key);
        if (!used[found] || keys[found] != key) {
            claim(found, key, target, now);
        } else {
            float value = values[found];
            if (target > 0.5F && value <= 0.001F) {
                since[found] = now;
            }
            float dt = Math.min(200, now - updated[found]);
            value += (target - value) * (1.0F - (float) Math.exp(-dt / EASE_MILLIS));
            values[found] = Math.abs(target - value) < 0.002F ? target : value;
            updated[found] = now;
        }
        slot = found;
        return values[found];
    }

    /** Milliseconds since the control eased last turned on, after {@link #ease}. */
    static long since() {
        return Util.getMillis() - since[slot];
    }

    /** Forgets every control (a preset switch: Potato's instant values shouldn't ease back). */
    static void reset() {
        for (int i = 0; i < SLOTS; i++) {
            used[i] = false;
        }
    }

    /** The control's slot, or the least recently drawn one to give it. */
    private static int find(long key) {
        int oldest = 0;
        long oldestTime = Long.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (!used[i]) {
                if (oldestTime != Long.MIN_VALUE) {
                    oldest = i;
                    oldestTime = Long.MIN_VALUE;
                }
                continue;
            }
            if (keys[i] == key) {
                return i;
            }
            if (updated[i] < oldestTime) {
                oldest = i;
                oldestTime = updated[i];
            }
        }
        return oldest;
    }

    private static void claim(int i, long key, float value, long now) {
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        updated[i] = now;
        since[i] = value > 0.5F ? now - 10_000 : now;
    }
}
