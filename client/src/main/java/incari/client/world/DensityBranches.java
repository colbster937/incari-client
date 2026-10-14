package incari.client.world;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * World generation's density functions sampled only where their values are used (like C2ME's lazy
 * branches). 26.3 samples a range choice's two branches over the whole volume, then picks one per
 * point. The overworld's caves are one: the sky sampled the whole cave stack, and the deep
 * underground the cave entrances.
 *
 * <p>Here the deciding side is sampled first, then each branch only over the rows (heights) some
 * point uses, at full width. The values are the game's exactly: a branch's value at a point
 * doesn't depend on the volume (noises and caches are functions of position), as long as rows
 * start on an interpolation cell's start, so a row range starts at the volume's start or a
 * multiple of {@link #ALIGN}.
 *
 * <p>Not done for {@code min} and {@code max} (whose point path skips the right side when the left
 * is out of its range): the right side was needed nearly everywhere, and scanning for where it
 * isn't made terrain 4% slower on the JVM.
 *
 * <p>Cached functions are sampled no more often than in the game: one asked for rows it holds
 * copies them ({@link #copyRows}), and one asked for a branch's rows caches the whole volume
 * ({@link #wholeVolume}). Only uncached work is skipped.
 */
public final class DensityBranches {

    /**
     * Rows start at a height that is a multiple of every interpolation cell's height (settings'
     * cells are 4, 8, 12 or 16 blocks high): an interpolated function steps its value up a cell
     * from the cell's first block, so a volume starting partway into a cell rounds differently.
     */
    private static final int ALIGN = 48;

    private static boolean enabled;

    /**
     * The volumes whose rows are being sampled on this thread, innermost last: a cached function
     * asked for rows of one is cached over all of it ({@link #wholeVolume}).
     */
    private static final ThreadLocal<java.util.ArrayList<DensityVolume>> SAMPLING =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    private DensityBranches() {}

    /** Off unless the page turns it on ({@code ?densitychoices=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code RangeChoiceFunction.Sampler.sampleVolume}): a range choice over a volume, or
     * false for the game's way. Hot path.
     */
    public static boolean rangeChoice(
            SamplerContext context,
            DensityBuffer output,
            DensityVolume volume,
            DensitySampler input,
            float minInclusive,
            float maxExclusive,
            DensitySampler whenInRange,
            DensitySampler whenOutOfRange) {
        // Only where a point stands for a cell's worth of blocks (a volume of cell corners): in a
        // volume of single blocks, the branches left are cheap and reading every point for them
        // isn't (the ore veins' choices by height came out slower).
        if (!enabled || volume.stepBlockY() == 1) {
            return false;
        }
        int sizeY = volume.sizeY();
        int columns = volume.sizeX() * volume.sizeZ();
        try (ScopedDensityBuffer inputs = context.acquireBuffer(volume)) {
            input.sampleVolume(context, inputs, volume);
            int lowIn = sizeY;
            int highIn = -1;
            int lowOut = sizeY;
            int highOut = -1;
            // Each column only below the lowest and above the highest row found so far: once the
            // first columns set the bounds, most columns need a few points looked at, not all.
            for (int column = 0; column < columns; column++) {
                int base = column * sizeY;
                for (int y = 0; y < lowIn; y++) {
                    if (inRange(inputs.get(base + y), minInclusive, maxExclusive)) {
                        lowIn = y;
                        break;
                    }
                }
                for (int y = sizeY - 1; y > highIn; y--) {
                    if (inRange(inputs.get(base + y), minInclusive, maxExclusive)) {
                        highIn = y;
                        break;
                    }
                }
                for (int y = 0; y < lowOut; y++) {
                    if (!inRange(inputs.get(base + y), minInclusive, maxExclusive)) {
                        lowOut = y;
                        break;
                    }
                }
                for (int y = sizeY - 1; y > highOut; y--) {
                    if (!inRange(inputs.get(base + y), minInclusive, maxExclusive)) {
                        highOut = y;
                        break;
                    }
                }
            }
            if (highOut < 0) {
                whenInRange.sampleVolume(context, output, volume);
                return true;
            }
            if (highIn < 0) {
                whenOutOfRange.sampleVolume(context, output, volume);
                return true;
            }
            sampleRows(context, whenInRange, volume, lowIn, highIn, output);
            try (ScopedDensityBuffer outside = context.acquireBuffer(volume)) {
                int start = sampleRows(context, whenOutOfRange, volume, lowOut, highOut, outside);
                for (int column = 0; column < columns; column++) {
                    int base = column * sizeY;
                    for (int y = start; y <= highOut; y++) {
                        float value = inputs.get(base + y);
                        if (!inRange(value, minInclusive, maxExclusive)) {
                            output.set(base + y, outside.get(base + y));
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Hook ({@code LerpFunction.Sampler.sampleVolume}): a lerp over a volume, its first side sampled
     * only over the rows where some point's alpha isn't 1 and its second where it isn't 0, as the
     * game's point path skips them; or false for the game's way. Hot path.
     */
    public static boolean lerp(
            SamplerContext context,
            DensityBuffer output,
            DensityVolume volume,
            DensitySampler alpha,
            DensitySampler first,
            DensitySampler second) {
        if (!enabled || volume.stepBlockY() == 1) {
            return false;
        }
        alpha.sampleVolume(context, output, volume);
        int sizeY = volume.sizeY();
        int columns = volume.sizeX() * volume.sizeZ();
        int lowFirst = sizeY;
        int highFirst = -1;
        int lowSecond = sizeY;
        int highSecond = -1;
        for (int column = 0; column < columns; column++) {
            int base = column * sizeY;
            for (int y = 0; y < lowFirst; y++) {
                if (output.get(base + y) != 1.0F) {
                    lowFirst = y;
                    break;
                }
            }
            for (int y = sizeY - 1; y > highFirst; y--) {
                if (output.get(base + y) != 1.0F) {
                    highFirst = y;
                    break;
                }
            }
            for (int y = 0; y < lowSecond; y++) {
                if (output.get(base + y) != 0.0F) {
                    lowSecond = y;
                    break;
                }
            }
            for (int y = sizeY - 1; y > highSecond; y--) {
                if (output.get(base + y) != 0.0F) {
                    highSecond = y;
                    break;
                }
            }
        }
        try (ScopedDensityBuffer firsts = context.acquireBuffer(volume);
                ScopedDensityBuffer seconds = context.acquireBuffer(volume)) {
            if (highFirst >= 0) {
                sampleRows(context, first, volume, lowFirst, highFirst, firsts);
            }
            if (highSecond >= 0) {
                sampleRows(context, second, volume, lowSecond, highSecond, seconds);
            }
            // The game's loop: a point whose alpha is 0 or 1 reads only the side it takes, which
            // its rows hold.
            for (int i = 0; i < output.size(); i++) {
                float a = output.get(i);
                if (a == 0.0F) {
                    output.set(i, firsts.get(i));
                } else if (a == 1.0F) {
                    output.set(i, seconds.get(i));
                } else {
                    output.set(i, net.minecraft.util.Mth.lerp(a, firsts.get(i), seconds.get(i)));
                }
            }
        }
        return true;
    }

    /** The game's test for the branch a point takes: a NaN takes the out of range one. */
    private static boolean inRange(float value, float minInclusive, float maxExclusive) {
        return value >= minInclusive && value < maxExclusive;
    }

    /**
     * Hook ({@code SamplerContext.sampleVolumeCached}): a cached volume's rows copied out for a
     * volume that is those rows of it ({@link #isRowsOf}). False if it isn't.
     */
    public static boolean copyRows(
            DensityVolume cached, DensityBuffer values, DensityVolume volume, DensityBuffer output) {
        if (!enabled || !isRowsOf(cached, volume)) {
            return false;
        }
        int first = (volume.minBlockY() - cached.minBlockY()) / cached.stepBlockY();
        int rows = volume.sizeY();
        int columns = volume.sizeX() * volume.sizeZ();
        for (int column = 0; column < columns; column++) {
            int from = column * cached.sizeY() + first;
            int to = column * rows;
            for (int y = 0; y < rows; y++) {
                output.set(to + y, values.get(from + y));
            }
        }
        return true;
    }

    /**
     * Hook ({@code SamplerContext.sampleVolumeCached}, when its cache misses): the volume to cache a
     * function over when it's asked for {@code volume}. Rows of a volume being sampled a branch at a
     * time are cached as all of that volume, as the game would have: the other branch, or another
     * function sharing the cache, asks for the other rows or for all of them next, and caching only
     * the rows would sample the cached function again for each. Otherwise {@code volume} itself.
     */
    public static DensityVolume wholeVolume(DensityVolume volume) {
        if (!enabled) {
            return volume;
        }
        java.util.ArrayList<DensityVolume> sampling = SAMPLING.get();
        for (int i = sampling.size() - 1; i >= 0; i--) {
            DensityVolume whole = sampling.get(i);
            if (!whole.equals(volume) && isRowsOf(whole, volume)) {
                return whole;
            }
        }
        return volume;
    }

    /**
     * Whether {@code volume} is rows of {@code whole} (the same columns and steps) that start at
     * its start or where {@link #rowStart} lets rows start, so that every value in it is the value
     * at the same point of {@code whole}.
     */
    private static boolean isRowsOf(DensityVolume whole, DensityVolume volume) {
        if (volume.sizeX() != whole.sizeX()
                || volume.sizeZ() != whole.sizeZ()
                || volume.minBlockX() != whole.minBlockX()
                || volume.minBlockZ() != whole.minBlockZ()
                || volume.stepBlockX() != whole.stepBlockX()
                || volume.stepBlockY() != whole.stepBlockY()
                || volume.stepBlockZ() != whole.stepBlockZ()) {
            return false;
        }
        int offset = volume.minBlockY() - whole.minBlockY();
        if (offset < 0 || offset % whole.stepBlockY() != 0) {
            return false;
        }
        if (offset != 0 && Math.floorMod(volume.minBlockY(), ALIGN) != 0) {
            return false;
        }
        return offset / whole.stepBlockY() + volume.sizeY() <= whole.sizeY();
    }

    /**
     * Samples {@code sampler} over rows {@code low} to {@code high} of {@code volume} (the start
     * moved down to where rows may start) into those rows of {@code target}, and returns the row
     * it started at.
     */
    private static int sampleRows(
            SamplerContext context,
            DensitySampler sampler,
            DensityVolume volume,
            int low,
            int high,
            DensityBuffer target) {
        int start = rowStart(volume, low);
        int sizeY = volume.sizeY();
        if (start == 0 && high == sizeY - 1) {
            sampler.sampleVolume(context, target, volume);
            return 0;
        }
        int rows = high - start + 1;
        DensityVolume part = new DensityVolume(
                volume.sizeX(),
                rows,
                volume.sizeZ(),
                volume.minBlockX(),
                volume.blockY(start),
                volume.minBlockZ(),
                volume.stepBlockX(),
                volume.stepBlockY(),
                volume.stepBlockZ());
        java.util.ArrayList<DensityVolume> sampling = SAMPLING.get();
        sampling.add(volume);
        try (ScopedDensityBuffer values = context.acquireBuffer(part)) {
            sampler.sampleVolume(context, values, part);
            int columns = volume.sizeX() * volume.sizeZ();
            for (int column = 0; column < columns; column++) {
                int from = column * rows;
                int to = column * sizeY + start;
                for (int y = 0; y < rows; y++) {
                    target.set(to + y, values.get(from + y));
                }
            }
        } finally {
            sampling.remove(sampling.size() - 1);
        }
        return start;
    }

    /** The highest row at or below {@code low} where a part of the volume may start. */
    private static int rowStart(DensityVolume volume, int low) {
        for (int y = low; y > 0; y--) {
            if (Math.floorMod(volume.blockY(y), ALIGN) == 0) {
                return y;
            }
        }
        return 0;
    }
}
