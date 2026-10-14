package incari.client.render.mesher;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.jspecify.annotations.Nullable;

/**
 * Finds out how a block state model picks its parts, without looking inside it: it collects the
 * model's parts with a random source that plays back chosen draws and records what the model asked
 * for. The game's models ask for one of three things, which become the mesher's model nodes
 * ({@code incari.mesher.MeshTables}): nothing (always the same parts), one {@code nextInt(total)}
 * (a weighted choice), or a {@code nextLong} whose value reseeds the random before each of several
 * children (a multipart model, each child again one of the first two). Anything else can't be
 * described, and the state is meshed by the game.
 */
final class ModelExplorer {

    /** Turns a part into its index in the tables. */
    private final ToIntFunction<BlockStateModelPart> partIndex;

    private final Script random = new Script();
    private final List<BlockStateModelPart> output = new ArrayList<>();

    ModelExplorer(ToIntFunction<BlockStateModelPart> partIndex) {
        this.partIndex = partIndex;
    }

    /** What a model does, as the table builder's calls; null if it can't be described. */
    @Nullable
    Node explore(BlockStateModel model) {
        if (!record(model, new int[0])) {
            return null;
        }
        List<Event> events = new ArrayList<>(random.events);
        List<BlockStateModelPart> parts = new ArrayList<>(output);
        if (events.isEmpty()) {
            return new Node.Fixed(indices(parts, 0, parts.size()));
        }
        Event first = events.getFirst();
        if (first.kind == Event.INT) {
            return weighted(model, first.bound, 0, 1, 0);
        }
        if (first.kind != Event.LONG) {
            return null;
        }
        // A multipart model: the random reseeded before each child.
        List<int[]> childDraws = new ArrayList<>();
        List<Integer> childStarts = new ArrayList<>();
        int draws = 0;
        int child = -1;
        for (int i = 1; i < events.size(); i++) {
            Event event = events.get(i);
            if (event.kind == Event.SET_SEED) {
                if (event.value != Script.SEED_MARK) {
                    return null;
                }
                child++;
                childDraws.add(new int[] {-1, 0});
                childStarts.add(event.outputSize);
            } else if (event.kind == Event.INT && child >= 0 && childDraws.get(child)[0] == -1) {
                childDraws.get(child)[0] = draws++;
                childDraws.get(child)[1] = event.bound;
            } else {
                return null;
            }
        }
        List<Node> children = new ArrayList<>();
        for (int c = 0; c <= child; c++) {
            int start = childStarts.get(c);
            int end = c < child ? childStarts.get(c + 1) : parts.size();
            int[] draw = childDraws.get(c);
            if (draw[0] == -1) {
                children.add(new Node.Fixed(indices(parts, start, end)));
            } else {
                Node node = weighted(model, draw[1], draw[0], draws, c);
                if (node == null) {
                    return null;
                }
                children.add(node);
            }
        }
        return new Node.Multipart(children);
    }

    /**
     * Explores a weighted choice: the model's {@code draw}-th draw (of {@code draws}) takes each
     * value up to {@code total} in turn, the others 0, and the parts it yields (those of multipart
     * child {@code child}, or all of them) are recorded for each.
     */
    private @Nullable Node weighted(BlockStateModel model, int total, int draw, int draws, int child) {
        IntArrayList weights = new IntArrayList();
        List<Node> choices = new ArrayList<>();
        int[] previous = null;
        int[] script = new int[draws];
        for (int value = 0; value < total; value++) {
            script[draw] = value;
            if (!record(model, script) || random.draws != draws) {
                return null;
            }
            int[] parts = childParts(child, draws > 1 || random.startsWithLong());
            if (parts == null) {
                return null;
            }
            if (previous != null && java.util.Arrays.equals(previous, parts)) {
                weights.set(weights.size() - 1, weights.getInt(weights.size() - 1) + 1);
            } else {
                weights.add(1);
                choices.add(new Node.Fixed(parts));
                previous = parts;
            }
        }
        return new Node.Weighted(weights.toIntArray(), choices);
    }

    /** The parts the last recording yielded: multipart child {@code child}'s, or all of them. */
    private int @Nullable [] childParts(int child, boolean multipart) {
        if (!multipart) {
            return indices(output, 0, output.size());
        }
        int seen = -1;
        int start = -1;
        int end = output.size();
        for (Event event : random.events) {
            if (event.kind == Event.SET_SEED) {
                seen++;
                if (seen == child) {
                    start = event.outputSize;
                } else if (seen == child + 1) {
                    end = event.outputSize;
                }
            }
        }
        return start < 0 ? null : indices(output, start, end);
    }

    private int[] indices(List<BlockStateModelPart> parts, int from, int to) {
        int[] indices = new int[to - from];
        for (int i = from; i < to; i++) {
            indices[i - from] = partIndex.applyAsInt(parts.get(i));
        }
        return indices;
    }

    /** Collects the model's parts with the random playing {@code script}; false if it did anything else. */
    private boolean record(BlockStateModel model, int[] script) {
        output.clear();
        random.start(script, output);
        try {
            model.collectParts(random, output);
        } catch (RuntimeException e) {
            return false;
        }
        return !random.failed;
    }

    /** A model's way of choosing parts, as the mesher's model nodes express it. */
    sealed interface Node {

        /** Always these parts. */
        @SuppressWarnings("ArrayRecordComponent") // never compared or hashed
        record Fixed(int[] parts) implements Node {}

        /** One of {@code choices}, drawn by weight. */
        @SuppressWarnings("ArrayRecordComponent") // never compared or hashed
        record Weighted(int[] weights, List<Node> choices) implements Node {}

        /** Each child, the random reseeded for each from one draw. */
        record Multipart(List<Node> children) implements Node {}
    }

    private record Event(int kind, int bound, long value, int outputSize) {
        static final int INT = 0;
        static final int LONG = 1;
        static final int SET_SEED = 2;
    }

    /** Plays back chosen {@code nextInt} values and records every call; anything else fails the recording. */
    private static final class Script implements RandomSource {

        /** What {@code nextLong} answers, so a reseed with it can be recognised. */
        static final long SEED_MARK = 0x1CA91L;

        final List<Event> events = new ArrayList<>();
        int[] script = new int[0];
        int draws;
        boolean failed;
        List<BlockStateModelPart> output = List.of();

        void start(int[] script, List<BlockStateModelPart> output) {
            this.script = script;
            this.output = output;
            events.clear();
            draws = 0;
            failed = false;
        }

        boolean startsWithLong() {
            return !events.isEmpty() && events.getFirst().kind == Event.LONG;
        }

        @Override
        public int nextInt(int bound) {
            events.add(new Event(Event.INT, bound, 0, output.size()));
            int value = draws < script.length ? script[draws] : 0;
            draws++;
            return value;
        }

        @Override
        public long nextLong() {
            events.add(new Event(Event.LONG, 0, 0, output.size()));
            return SEED_MARK;
        }

        @Override
        public void setSeed(long seed) {
            events.add(new Event(Event.SET_SEED, 0, seed, output.size()));
        }

        private <T> T fail(T value) {
            failed = true;
            return value;
        }

        @Override
        public RandomSource fork() {
            return fail(this);
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            failed = true;
            throw new UnsupportedOperationException("forkPositional while exploring a model");
        }

        @Override
        public int nextInt() {
            return fail(0);
        }

        @Override
        public boolean nextBoolean() {
            return fail(false);
        }

        @Override
        public float nextFloat() {
            return fail(0.0F);
        }

        @Override
        public double nextDouble() {
            return fail(0.0);
        }

        @Override
        public double nextGaussian() {
            return fail(0.0);
        }
    }
}
