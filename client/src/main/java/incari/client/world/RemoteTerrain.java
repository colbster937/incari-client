package incari.client.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import incari.client.platform.ClientPlatform;
import incari.platform.api.threads.PlatformChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Builds chunks' terrain (noise, surface, carvers) in helper workers beside singleplayer's server
 * (like C2ME's parallel world generation; ADR 0042). The server's one thread also ticks, decorates
 * and saves, and terrain was over half its work while exploring. A helper is a copy of the game
 * with only the world's data packs and generators: the server sends it a {@link TerrainJob},
 * carries on, and puts the blocks into the chunk when they come back.
 *
 * <p>A few jobs are out per helper and the server builds the rest, so a slow helper never holds
 * the world up. Only jobs the helper builds exactly as the server would go out: a new chunk
 * (nothing to blend with, nothing written yet) of a noise generator the helper has. The server
 * builds anything else, including jobs a helper can't do.
 *
 * <p>Both sides are here: the server's ({@link #start}, the hooks) and the helper's ({@link
 * #serve}).
 */
public final class RemoteTerrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Server to helper: the world's data packs, to load while the server loads its own (NBT). */
    static final int DATA = 1;
    /** Server to helper: the world's generators, once its levels exist (NBT). */
    static final int GENERATORS = 7;
    /** Helper to server: set up, jobs welcome. */
    static final int READY = 2;
    /** Helper to server: can't make this world's terrain exactly (the reason, UTF-8). */
    static final int REFUSED = 3;
    /** Server to helper: a chunk's job. */
    static final int JOB = 4;
    /** Helper to server: a job's result. */
    static final int RESULT = 5;
    /** Helper to server: a job it couldn't do (its id). */
    static final int FAILED = 6;

    /** Jobs out per helper: one being built and one waiting, so it's never idle between them. */
    private static final int JOBS_PER_HELPER = 2;

    private static volatile @Nullable RemoteTerrain active;
    private static @Nullable RemoteTerrain prepared;
    private static int helperCount;

    // The helper's side: the job being built, whose structures came with it.
    private static volatile @Nullable Beardifier givenBeardifier;
    private static volatile @Nullable ChunkPos givenAt;

    private final List<Helper> helpers = new ArrayList<>();
    private final IdentityHashMap<NoiseBasedChunkGenerator, Integer> generators = new IdentityHashMap<>();
    private final IdentityHashMap<NoiseBasedChunkGenerator, LevelHeightAccessor> heights = new IdentityHashMap<>();
    /** Chunks the server builds itself after a helper couldn't: the hook lets them through. */
    private final Set<ChunkAccess> local = Collections.newSetFromMap(new IdentityHashMap<>());

    private int nextJob;
    private int jobsSent;
    private int jobsLocal;

    private RemoteTerrain() {}

    // --- The server's side ---------------------------------------------------------------------

    /**
     * How many helpers a server starts: none unless the page says ({@code ?terrainworkers=N},
     * {@code GameWorkerMain}).
     */
    public static void setHelpers(int count) {
        helperCount = Math.max(0, count);
    }

    /**
     * Starts the helpers (from {@code WorldWorker}, before any world comes): they start the game
     * while the player picks a world, as the server's worker does. Nothing goes to them until
     * {@link #start}.
     */
    public static void prepare() {
        int count = helperCount;
        if (count <= 0 || prepared != null) {
            return;
        }
        RemoteTerrain remote = new RemoteTerrain();
        for (int i = 0; i < count; i++) {
            PlatformChannel channel = ClientPlatform.get().threads().startGameWorker("terrain");
            if (channel == null) {
                LOGGER.info("Terrain helpers: this platform can't start workers; building terrain here");
                break;
            }
            Helper helper = remote.new Helper(channel, i);
            remote.helpers.add(helper);
            Thread reader = new Thread(helper::read, "Terrain helper " + i);
            reader.setDaemon(true);
            reader.start();
        }
        if (!remote.helpers.isEmpty()) {
            prepared = remote;
            LOGGER.info("Terrain helpers: {} starting", remote.helpers.size());
        }
    }

    /**
     * The world's data packs, as its {@code level.dat} has them (from {@code WorldWorker}, before
     * the server loads them): the helpers load theirs meanwhile.
     */
    public static void loadData(WorldDataConfiguration data) {
        RemoteTerrain remote = prepared;
        if (remote == null) {
            return;
        }
        byte[] bytes = nbt(wrap(
                WorldDataConfiguration.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow()));
        for (Helper helper : remote.helpers) {
            helper.channel.send(DATA, bytes, 0, bytes.length);
        }
    }

    /**
     * The server's levels exist (from {@code WorkerServer.initServer}): their generators go to the
     * helpers, and jobs to each helper once it says it's ready. Until then, and if none is, the
     * server builds every chunk itself.
     */
    public static void start(MinecraftServer server) {
        RemoteTerrain remote = prepared;
        if (remote == null) {
            return;
        }
        CompoundTag generators;
        try {
            generators = remote.generators(server);
        } catch (RuntimeException e) {
            LOGGER.warn("Terrain helpers: can't describe this world's generators; building terrain here", e);
            return;
        }
        byte[] bytes = nbt(generators);
        for (Helper helper : remote.helpers) {
            helper.channel.send(GENERATORS, bytes, 0, bytes.length);
        }
        active = remote;
    }

    /** Lets the helpers go (the server has stopped). */
    public static void stop() {
        RemoteTerrain remote = prepared;
        active = null;
        prepared = null;
        if (remote != null) {
            LOGGER.info(
                    "Terrain helpers: {} chunks' terrain built by helpers, {} here after a helper couldn't",
                    remote.jobsSent,
                    remote.jobsLocal);
            for (Helper helper : remote.helpers) {
                helper.channel.close();
            }
        }
    }

    /** Each noise generator, with the seed and height it builds for, and how the biomes are numbered. */
    private CompoundTag generators(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        CompoundTag setup = new CompoundTag();
        setup.putInt("biomes", biomeNumbering(registries));
        ListTag list = new ListTag();
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                    || generators.containsKey(generator)) {
                continue;
            }
            int id = generators.size();
            CompoundTag entry = new CompoundTag();
            entry.putInt("id", id);
            entry.putLong("seed", level.getSeed());
            entry.putInt("min_y", level.getMinY());
            entry.putInt("height", level.getHeight());
            entry.put(
                    "generator",
                    ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow());
            list.add(entry);
            generators.put(generator, id);
            heights.put(generator, LevelHeightAccessor.create(level.getMinY(), level.getHeight()));
        }
        setup.put("generators", list);
        return setup;
    }

    /**
     * Hook ({@code NoiseBasedChunkGenerator.buildTerrain}): the chunk's terrain built by a helper,
     * or null to build it here.
     */
    // The empty blender is one object: the game hands that one out wherever there's no blending.
    @SuppressWarnings("ReferenceEquality")
    public static @Nullable CompletableFuture<ChunkAccess> buildTerrain(
            NoiseBasedChunkGenerator generator,
            ChunkAccess chunk,
            Blender blender,
            StructureManager structures,
            @Nullable WorldGenRegion region) {
        RemoteTerrain remote = active;
        if (remote == null || region == null || blender != Blender.empty()) {
            return null;
        }
        return remote.submit(generator, chunk, structures, region);
    }

    private @Nullable CompletableFuture<ChunkAccess> submit(
            NoiseBasedChunkGenerator generator, ChunkAccess chunk, StructureManager structures, WorldGenRegion region) {
        Integer id = generators.get(generator);
        if (id == null || !fresh(chunk, generator)) {
            return null;
        }
        Helper helper;
        Pending pending;
        int job;
        synchronized (this) {
            if (local.remove(chunk)) {
                return null;
            }
            helper = leastBusy();
            if (helper == null) {
                return null;
            }
            job = nextJob++;
            pending = new Pending(chunk, generator, structures, region);
            helper.pending.put(job, pending);
            jobsSent++;
        }
        byte[] bytes;
        try {
            bytes = TerrainJob.encode(
                    job, id, chunk, region::getChunk, Beardifier.forStructuresInChunk(structures, chunk.getPos()));
        } catch (RuntimeException e) {
            LOGGER.warn("Terrain helpers: couldn't write the job for {}", chunk.getPos(), e);
            synchronized (this) {
                helper.pending.remove(job);
                jobsSent--;
            }
            return null;
        }
        helper.channel.send(JOB, bytes, 0, bytes.length);
        return pending.future;
    }

    /**
     * A chunk the helper would build exactly as the server: a proto-chunk of this generator's level,
     * nothing written into it yet, not being upgraded from an old world.
     */
    private boolean fresh(ChunkAccess chunk, NoiseBasedChunkGenerator generator) {
        if (chunk.getClass() != ProtoChunk.class || chunk.isUpgrading()) {
            return false;
        }
        ProtoChunk proto = (ProtoChunk) chunk;
        LevelHeightAccessor height = heights.get(generator);
        if (proto.getBelowZeroRetrogen() != null
                || height == null
                || chunk.getMinY() != height.getMinY()
                || chunk.getHeight() != height.getHeight()
                || !chunk.getHeightmaps().isEmpty()) {
            return false;
        }
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                return false;
            }
        }
        for (Object list : chunk.getPostProcessing()) {
            if (list != null) {
                return false;
            }
        }
        return true;
    }

    private @Nullable Helper leastBusy() {
        Helper best = null;
        for (Helper helper : helpers) {
            if (helper.ready
                    && helper.pending.size() < JOBS_PER_HELPER
                    && (best == null || helper.pending.size() < best.pending.size())) {
                best = helper;
            }
        }
        return best;
    }

    /** Builds a job's chunk here after all; its future completes as the game's own would. */
    // The region's level is the one the game's own buildTerrain takes its random state from; the
    // future it returns completes the job's, so nothing is lost by not keeping it.
    @SuppressWarnings({"deprecation", "FutureReturnValueIgnored"})
    private void buildHere(Pending pending) {
        synchronized (this) {
            local.add(pending.chunk);
            jobsLocal++;
        }
        ChunkAccess chunk = pending.chunk;
        WorldGenRegion region = pending.region;
        ServerLevel level = region.getLevel();
        Set<Holder<Biome>> possibleBiomes = new java.util.HashSet<>();
        ChunkPos pos = chunk.getPos();
        for (int z = pos.z() - 1; z <= pos.z() + 1; z++) {
            for (int x = pos.x() - 1; x <= pos.x() + 1; x++) {
                region.getChunk(x, z).collectBiomesInPalette(possibleBiomes);
            }
        }
        pending.generator
                .buildTerrain(
                        chunk,
                        Blender.empty(),
                        level.getChunkSource().randomState(),
                        pending.structures,
                        region.getBiomeManager(),
                        region,
                        possibleBiomes)
                .whenComplete((built, error) -> {
                    if (error != null) {
                        pending.future.completeExceptionally(error);
                    } else {
                        pending.future.complete(built);
                    }
                });
    }

    /** A job out at a helper: the chunk it fills, and what building it here would take. */
    private static final class Pending {
        final ChunkAccess chunk;
        final NoiseBasedChunkGenerator generator;
        final StructureManager structures;
        final WorldGenRegion region;
        final CompletableFuture<ChunkAccess> future = new CompletableFuture<>();

        Pending(
                ChunkAccess chunk,
                NoiseBasedChunkGenerator generator,
                StructureManager structures,
                WorldGenRegion region) {
            this.chunk = chunk;
            this.generator = generator;
            this.structures = structures;
            this.region = region;
        }
    }

    /** One helper worker, and the jobs it has. */
    private final class Helper {
        final PlatformChannel channel;
        final int index;
        final Map<Integer, Pending> pending = new HashMap<>();
        volatile boolean ready;

        Helper(PlatformChannel channel, int index) {
            this.channel = channel;
            this.index = index;
        }

        /** The helper's answers, on a thread of their own, until it ends. */
        void read() {
            while (true) {
                PlatformChannel.Message message = channel.receive();
                if (message == null) {
                    ended();
                    return;
                }
                switch (message.kind()) {
                    case READY -> {
                        ready = true;
                        LOGGER.info("Terrain helper {} is ready", index);
                    }
                    case REFUSED ->
                        LOGGER.info(
                                "Terrain helper {} can't build this world's terrain: {}",
                                index,
                                new String(message.bytes(), StandardCharsets.UTF_8));
                    case RESULT -> {
                        Pending job = take(TerrainJob.resultId(message.bytes()));
                        if (job != null) {
                            try {
                                TerrainJob.apply(message.bytes(), job.chunk);
                                job.future.complete(job.chunk);
                            } catch (RuntimeException e) {
                                LOGGER.error("Terrain helper {}: a result didn't fit {}", index, job.chunk.getPos(), e);
                                job.future.completeExceptionally(e);
                            }
                        }
                    }
                    case FAILED -> {
                        Pending job = take(TerrainJob.resultId(message.bytes()));
                        if (job != null) {
                            buildHere(job);
                        }
                    }
                    default -> LOGGER.warn("Terrain helper {}: unknown message {}", index, message.kind());
                }
            }
        }

        private @Nullable Pending take(int job) {
            synchronized (RemoteTerrain.this) {
                return pending.remove(job);
            }
        }

        /** The helper is gone: what it had is built here. */
        private void ended() {
            List<Pending> left;
            synchronized (RemoteTerrain.this) {
                ready = false;
                left = new ArrayList<>(pending.values());
                pending.clear();
            }
            if (active != null) {
                LOGGER.warn("Terrain helper {} ended; {} jobs built here instead", index, left.size());
            }
            for (Pending job : left) {
                buildHere(job);
            }
        }
    }

    // --- The helper's side ---------------------------------------------------------------------

    /**
     * Hook ({@code NoiseBasedChunkGenerator.createNoiseChunk}): in a helper, the structures that came
     * with the job; elsewhere the game's.
     */
    public static Beardifier beardifier(@Nullable StructureManager structures, ChunkPos pos) {
        Beardifier given = givenBeardifier;
        if (given != null && pos.equals(givenAt)) {
            return given;
        }
        if (structures == null) {
            throw new IllegalStateException("No structures for " + pos);
        }
        return Beardifier.forStructuresInChunk(structures, pos);
    }

    /**
     * The helper worker's program (role {@code terrain}), once the platform is installed: sets up as
     * the server says, then builds jobs one at a time until the server lets it go.
     */
    public static void serve() {
        PlatformChannel server = ClientPlatform.get().threads().parentChannel();
        if (server == null) {
            throw new IllegalStateException("Not a worker started by a server: no channel to it");
        }
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Map<Integer, Built> built;
        try {
            PlatformChannel.Message data = server.receive();
            if (data == null || data.kind() != DATA) {
                server.close();
                return;
            }
            RegistryAccess.Frozen registries =
                    loadRegistries(readNbt(data.bytes()).get("value"));
            PlatformChannel.Message generators = server.receive();
            if (generators == null || generators.kind() != GENERATORS) {
                server.close();
                return;
            }
            built = setUp(registries, readNbt(generators.bytes()));
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Terrain helper: can't set up", e);
            byte[] reason = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
            server.send(REFUSED, reason, 0, reason.length);
            server.close();
            return;
        }
        server.send(READY, new byte[0], 0, 0);
        while (true) {
            PlatformChannel.Message message = server.receive();
            if (message == null) {
                // The server let it go: end here rather than wait to be ended.
                server.close();
                return;
            }
            if (message.kind() != JOB) {
                continue;
            }
            byte[] answer;
            int kind;
            try {
                answer = build(message.bytes(), built);
                kind = RESULT;
            } catch (RuntimeException e) {
                LOGGER.warn("Terrain helper: a job failed", e);
                answer = message.bytes();
                kind = FAILED;
                // The job's id leads its bytes, as a result's does.
            }
            server.send(kind, answer, 0, answer.length);
        }
    }

    /** A generator the server set up, ready to build with. */
    record Built(
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            long seed,
            LevelHeightAccessor height,
            PalettedContainerFactory containers) {}

    /**
     * The registries of a world with these data packs ({@link WorldDataConfiguration}, as NBT), as
     * the server loads them; refused if one of its packs isn't built in.
     */
    static RegistryAccess.Frozen loadRegistries(@Nullable Tag dataTag) throws IOException {
        WorldDataConfiguration data =
                WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        // A pack of the world's own (its datapacks folder) isn't here: what it changes would be
        // missing from the terrain, not just the biomes' numbers.
        net.minecraft.server.packs.repository.PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        for (String pack : data.dataPacks().getEnabled()) {
            if (!packs.isAvailable(pack)) {
                throw new IOException("the world's data pack " + pack + " isn't built in");
            }
        }
        return loadRegistries(data);
    }

    /** The server's generators, made here with the same registries; refused if biomes are numbered otherwise. */
    static Map<Integer, Built> setUp(RegistryAccess.Frozen registries, CompoundTag setup) throws IOException {
        if (biomeNumbering(registries) != setup.getIntOr("biomes", 0)) {
            throw new IOException("the world's biomes are numbered otherwise here (data packs of its own?)");
        }
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        PalettedContainerFactory containers = PalettedContainerFactory.create(registries);
        Map<Integer, Built> built = new HashMap<>();
        for (Tag tag : setup.getListOrEmpty("generators")) {
            CompoundTag entry = (CompoundTag) tag;
            ChunkGenerator decoded =
                    ChunkGenerator.CODEC.parse(ops, entry.get("generator")).getOrThrow();
            if (!(decoded instanceof NoiseBasedChunkGenerator generator)) {
                continue;
            }
            long seed = entry.getLongOr("seed", 0);
            // As the server's ChunkMap makes it for the level.
            RandomState randomState = RandomState.create(
                    registries.lookupOrThrow(Registries.NOISE),
                    seed,
                    generator.generatorSettings().value());
            built.put(
                    entry.getIntOr("id", -1),
                    new Built(
                            generator,
                            randomState,
                            seed,
                            LevelHeightAccessor.create(entry.getIntOr("min_y", 0), entry.getIntOr("height", 0)),
                            containers));
        }
        return built;
    }

    /** One job, built as the server would: its result's bytes. */
    // No structure manager: the step's only use of it is the beardifier, which came with the job.
    @SuppressWarnings("NullAway")
    static byte[] build(byte[] bytes, Map<Integer, Built> built) {
        int generatorId = generatorOf(bytes);
        Built setUp = built.get(generatorId);
        if (setUp == null) {
            throw new IllegalStateException("No generator " + generatorId);
        }
        TerrainJob.Job job = TerrainJob.decode(bytes, setUp.height(), setUp.containers());
        ProtoChunk chunk = job.chunk();
        RandomState randomState = setUp.randomState();
        BiomeManager biomes = new BiomeManager(
                job.biomes(setUp.generator().getBiomeSource().createUncachedResolver(randomState)),
                BiomeManager.obfuscateSeed(setUp.seed()));
        givenBeardifier = job.beardifier();
        givenAt = chunk.getPos();
        try {
            setUp.generator()
                    .buildTerrain(chunk, Blender.empty(), randomState, null, biomes, null, job.possibleBiomes())
                    .join();
        } finally {
            givenBeardifier = null;
            givenAt = null;
        }
        return TerrainJob.result(job.id(), chunk);
    }

    /** The generator a job is for: its second number. */
    private static int generatorOf(byte[] bytes) {
        net.minecraft.network.FriendlyByteBuf in =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        in.readVarInt();
        return in.readVarInt();
    }

    /**
     * The registries a world with these data packs generates terrain with, loaded as the server
     * loads them up to there: the built-in registries' tags (blocks, for what carvers may carve),
     * then the world's data-driven registries (biomes, noise, density functions, generator
     * settings) with theirs. None of the server's reloadable data (recipes, advancements, loot
     * tables, functions), which a helper would hold for nothing: about half its heap.
     */
    private static RegistryAccess.Frozen loadRegistries(WorldDataConfiguration data) {
        WorldLoader.PackConfig packs =
                new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(), data, false, false);
        net.minecraft.server.packs.resources.CloseableResourceManager resources =
                packs.createResourceManager().getSecond();
        try {
            net.minecraft.core.LayeredRegistryAccess<net.minecraft.server.RegistryLayer> layers =
                    net.minecraft.server.RegistryLayer.createRegistryAccess();
            List<Registry.PendingTags<?>> staticTags = net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(
                    resources, layers.getLayer(net.minecraft.server.RegistryLayer.STATIC));
            RegistryAccess.Frozen forLoading = layers.getAccessForLoading(net.minecraft.server.RegistryLayer.WORLD);
            List<net.minecraft.core.HolderLookup.RegistryLookup<?>> context =
                    net.minecraft.tags.TagLoader.buildUpdatedLookups(forLoading, staticTags);
            RegistryAccess.Frozen world = net.minecraft.resources.RegistryDataLoader.load(
                            resources,
                            context,
                            net.minecraft.resources.RegistryDataLoader.WORLD_REGISTRIES,
                            Util.backgroundExecutor())
                    .join();
            // The server binds the built-in registries' tags once its resources load; nothing
            // else binds them here.
            staticTags.forEach(Registry.PendingTags::apply);
            return layers.replaceFrom(net.minecraft.server.RegistryLayer.WORLD, world)
                    .compositeAccess();
        } finally {
            resources.close();
        }
    }

    /** A number for how the biomes are numbered: equal on both sides if their palettes read alike. */
    static int biomeNumbering(RegistryAccess registries) {
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        int hash = biomes.size();
        for (int id = 0; id < biomes.size(); id++) {
            Biome biome = biomes.byId(id);
            hash = hash * 31
                    + String.valueOf(biome == null ? null : biomes.getKey(biome))
                            .hashCode();
        }
        return hash;
    }

    /** A tag that may not be a compound, as one: NBT files hold compounds. */
    private static CompoundTag wrap(Tag tag) {
        CompoundTag compound = new CompoundTag();
        compound.put("value", tag);
        return compound;
    }

    private static byte[] nbt(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag readNbt(byte[] bytes) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
}
