package incari.client.world;

import incari.client.platform.ClientPlatform;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.jspecify.annotations.Nullable;

/**
 * Concentric ring placements (the strongholds) worked out ring by ring, when a chunk asks, instead
 * of all at once when a level loads. The game picks each of the 128 strongholds' rough place from
 * one random sequence, then moves it to a nearby preferred biome with a search over a 225-block
 * square, and starts all 128 searches as background tasks the moment the overworld exists. A
 * page's worker has one thread for those tasks and for world generation, so every world opened
 * waited for all of them before its spawn area could generate: half the worker's work when a world
 * reopened, in the singleplayer benchmark's profiles.
 *
 * <p>Here the rough places and each search's random source come from the same sequence, in the
 * same order, up front (cheap); a search runs the first time an answer needs it. A chunk is a ring
 * position only if a ring's search could end there, and a search ends within its square, so a
 * chunk asks only the rings whose square reaches it: near spawn, none. A minute after the level
 * loads, the searches also run one at a time in the background, each queued after the last, so
 * asking for all of them later (an eye of ender, {@code /locate}) needn't wait, without a burst of
 * tasks in front of the spawn area. Each
 * search is the game's own call with the random source the game would give it, so every answer is
 * the game's.
 */
public final class StrongholdRings {

    /**
     * How long after a level loads the searches ahead of need begin: by then its spawn area has
     * loaded, which they would otherwise slow (they took half the worker's time reopening a world).
     */
    private static final long SEARCH_AHEAD_DELAY_SECONDS = 60;

    /** How long a search ahead of need waits for the background pool to be idle before it looks again. */
    private static final long QUIET_RETRY_MILLIS = 500;

    /** How far, in blocks, the game's search moves a ring from its rough place. */
    private static final int SEARCH_RADIUS = 112;

    private static boolean enabled;

    private StrongholdRings() {}

    /** Off unless the page turns it on ({@code ?rings=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code ChunkGeneratorStructureState.generateRingPositions}): the ring positions as a
     * list that works each one out when it's read, or null for the game's way. {@code background}
     * runs the searches ahead of need, one at a time, from a minute on.
     */
    public static @Nullable CompletableFuture<List<ChunkPos>> plan(
            ConcentricRingsStructurePlacement placement,
            long seed,
            BiomeSource biomes,
            RandomState randomState,
            Executor background) {
        if (!enabled) {
            return null;
        }
        Rings rings = new Rings(placement, seed, biomes, randomState);
        CompletableFuture.delayedExecutor(SEARCH_AHEAD_DELAY_SECONDS, TimeUnit.SECONDS, background)
                .execute(() -> rings.searchAhead(background, 0));
        return CompletableFuture.completedFuture(rings);
    }

    /**
     * The positions, in the game's order. Read from any thread.
     */
    static final class Rings extends AbstractList<ChunkPos> implements RandomAccess {

        private final BiomeSource biomes;
        private final RandomState randomState;
        private final HolderSet<Biome> preferred;
        /** Each ring's rough place, in chunks. */
        private final int[] roughX;

        private final int[] roughZ;
        /** Each ring's search's random source, untouched until its search runs. */
        private final RandomSource[] searchRandom;

        private final AtomicReferenceArray<ChunkPos> found;
        /** How far a search can move a ring, in chunks, to the west and north; then east and south. */
        private final int reachLow;

        private final int reachHigh;

        Rings(ConcentricRingsStructurePlacement placement, long seed, BiomeSource biomes, RandomState randomState) {
            this.biomes = biomes;
            this.randomState = randomState;
            preferred = placement.preferredBiomes();
            int count = placement.count();
            roughX = new int[count];
            roughZ = new int[count];
            searchRandom = new RandomSource[count];
            found = new AtomicReferenceArray<>(count);
            placeRoughly(placement, seed);
            // A search centred on a chunk's middle covers whole quarter-blocks out to its radius.
            int center = QuartPos.fromBlock(SectionPos.sectionToBlockCoord(0, 8));
            int radius = QuartPos.fromBlock(SEARCH_RADIUS);
            reachLow = SectionPos.blockToSectionCoord(QuartPos.toBlock(center - radius));
            reachHigh = SectionPos.blockToSectionCoord(QuartPos.toBlock(center + radius));
        }

        /**
         * The rough places, from the placement's random sequence: each ring of strongholds lies
         * further out than the last and holds more of them, spread evenly around it from a random
         * start. Each stronghold's search gets a random source forked from the sequence, in order.
         */
        private void placeRoughly(ConcentricRingsStructurePlacement placement, long seed) {
            int distance = placement.distance();
            int perRing = placement.spread();
            int count = placement.count();
            RandomSource random = RandomSource.create();
            random.setSeed(seed);
            double angle = random.nextDouble() * Math.PI * 2.0;
            int ring = 0;
            int inRing = 0;
            for (int i = 0; i < count; i++) {
                double radius = 4 * distance + distance * ring * 6 + (random.nextDouble() - 0.5) * (distance * 2.5);
                roughX[i] = (int) Math.round(Math.cos(angle) * radius);
                roughZ[i] = (int) Math.round(Math.sin(angle) * radius);
                searchRandom[i] = random.fork();
                angle += Math.PI * 2 / perRing;
                inRing++;
                if (inRing == perRing) {
                    ring++;
                    inRing = 0;
                    perRing += 2 * perRing / (ring + 1);
                    perRing = Math.min(perRing, count - i);
                    angle += random.nextDouble() * Math.PI * 2.0;
                }
            }
        }

        @Override
        public ChunkPos get(int index) {
            ChunkPos position = found.get(index);
            if (position != null) {
                return position;
            }
            // A search draws from its random source, so it runs once; anyone else asking waits for it.
            RandomSource random = searchRandom[index];
            synchronized (random) {
                position = found.get(index);
                if (position == null) {
                    position = search(index, random);
                    found.set(index, position);
                }
            }
            return position;
        }

        /** The game's search for ring {@code index}'s preferred biome, from its rough place. */
        private ChunkPos search(int index, RandomSource random) {
            var result = biomes.findBiomeHorizontal(
                    SectionPos.sectionToBlockCoord(roughX[index], 8),
                    0,
                    SectionPos.sectionToBlockCoord(roughZ[index], 8),
                    SEARCH_RADIUS,
                    preferred::contains,
                    random,
                    randomState);
            if (result == null) {
                return new ChunkPos(roughX[index], roughZ[index]);
            }
            BlockPos at = result.getFirst();
            return new ChunkPos(SectionPos.blockToSectionCoord(at.getX()), SectionPos.blockToSectionCoord(at.getZ()));
        }

        /** How many rings have been searched so far. */
        int searched() {
            int count = 0;
            for (int i = 0; i < found.length(); i++) {
                if (found.get(i) != null) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int size() {
            return found.length();
        }

        /** Whether {@code object} is a ring position, asking only the rings whose search reaches it. */
        @Override
        public boolean contains(@Nullable Object object) {
            if (!(object instanceof ChunkPos chunk)) {
                return false;
            }
            for (int i = 0; i < roughX.length; i++) {
                int dx = chunk.x() - roughX[i];
                int dz = chunk.z() - roughZ[i];
                if (dx >= reachLow && dx <= reachHigh && dz >= reachLow && dz <= reachHigh && get(i).equals(chunk)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Works out ring {@code index} as a background task, then queues the next. A search waits
         * while other background work is queued (generating the chunks around the player, mostly):
         * on a worker with one thread a search held up the chunks queued behind it.
         */
        void searchAhead(Executor background, int index) {
            if (index >= size()) {
                return;
            }
            background.execute(() -> {
                if (ClientPlatform.get().threads().backgroundQueued() > 0) {
                    CompletableFuture.delayedExecutor(QUIET_RETRY_MILLIS, TimeUnit.MILLISECONDS, background)
                            .execute(() -> searchAhead(background, index));
                    return;
                }
                get(index);
                searchAhead(background, index + 1);
            });
        }
    }
}
