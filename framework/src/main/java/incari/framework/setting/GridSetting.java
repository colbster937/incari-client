package incari.framework.setting;

import java.util.Arrays;

/**
 * A square of cells, each on or off: a crosshair drawn pixel by pixel. The settings screen edits it
 * in a grid of its own; the profile writes it as one hex number a row.
 */
public final class GridSetting extends Setting<GridSetting> {

    /** The largest side: a row fits a {@code long}. */
    public static final int MAX_SIZE = 63;

    private final int size;
    private final long[] defaultRows;
    private final long[] rows;

    /**
     * @param id the config key
     * @param name what the settings screen calls it
     * @param size cells a side, at most {@link #MAX_SIZE}
     * @param defaultRows each row's cells as bits, bit 0 the leftmost; copied
     */
    public GridSetting(String id, String name, int size, long[] defaultRows) {
        super(id, name);
        if (size < 1 || size > MAX_SIZE || defaultRows.length != size) {
            throw new IllegalArgumentException(id + ": a grid of " + size + " with " + defaultRows.length + " rows");
        }
        this.size = size;
        this.defaultRows = defaultRows.clone();
        this.rows = defaultRows.clone();
    }

    /** Cells a side. */
    public int size() {
        return size;
    }

    /** Whether the cell {@code x} across, {@code y} down is on. */
    public boolean get(int x, int y) {
        return (rows[y] >>> x & 1L) != 0;
    }

    /** Turns the cell {@code x} across, {@code y} down on or off. */
    public void set(int x, int y, boolean on) {
        long row = on ? rows[y] | 1L << x : rows[y] & ~(1L << x);
        if (row != rows[y]) {
            rows[y] = row;
            changed();
        }
    }

    /** Row {@code y}'s cells as bits, bit 0 the leftmost. */
    public long row(int y) {
        return rows[y];
    }

    /** Sets every row at once (a preset, a loaded profile); rows past the size are ignored. */
    public void setRows(long[] values) {
        boolean changed = false;
        long mask = size == 64 ? -1L : (1L << size) - 1;
        for (int y = 0; y < size; y++) {
            long row = y < values.length ? values[y] & mask : 0;
            if (rows[y] != row) {
                rows[y] = row;
                changed = true;
            }
        }
        if (changed) {
            changed();
        }
    }

    /** Turns every cell off. */
    public void clear() {
        setRows(new long[size]);
    }

    @Override
    public boolean isDefault() {
        return Arrays.equals(rows, defaultRows);
    }

    @Override
    public void reset() {
        setRows(defaultRows);
    }

    @Override
    public <R> R accept(SettingVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    protected GridSetting self() {
        return this;
    }
}
