package incari.client.render.mesher;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import incari.client.render.AnimatedSprites;
import incari.client.render.TerrainVertexFormat;
import incari.mesher.Bytes;
import incari.mesher.MeshJobs;
import incari.mesher.MeshResult;
import incari.mesher.MeshTables;
import incari.mesher.SectionMesher;
import incari.mesher.Snapshot;
import incari.platform.api.Platforms;
import incari.platform.api.threads.WorkerException;
import incari.platform.api.threads.WorkerJob;
import incari.platform.api.threads.WorkerPool;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the game's section compiles come to Incari's mesher (ADR 0023). Two hooks in the
 * game's section dispatcher call in here: one for each section compile, one for each re-sort of a
 * translucent layer; one in the section compiler's constructor says which models and settings the
 * compiles that follow use; one sizes the section buffers.
 *
 * <p>How a section is meshed: the game's own compiler ({@link Mode#VANILLA}, the desktop's default),
 * Incari's mesher ({@link Mode#INCARI}), or both, compared ({@link Mode#VERIFY},
 * {@code -Dincari.mesher=verify}). Incari's mesher runs in the platform's mesher workers where it
 * has them (the page's Web Workers), for compiles on the background pool; a compile the render
 * thread needs at once, or one on a platform without workers, is meshed where it's asked for.
 */
public final class Meshing {

    private static final Logger LOGGER = LoggerFactory.getLogger(Meshing.class);

    /** How sections are meshed. */
    public enum Mode {
        /** The game's section compiler. */
        VANILLA,
        /** Incari's mesher. */
        INCARI,
        /** Both, compared; the game's mesh is drawn. */
        VERIFY
    }

    /** The section buffers' starting size when they only ever hold finished meshes (Incari's). */
    private static final int SMALL_SECTION_BUFFER = 64 * 1024;

    private static volatile Mode mode = parseMode(System.getProperty("incari.mesher", "vanilla"));
    private static volatile boolean useWorkers;
    private static volatile boolean fixedOrder;

    /** The models and settings the current section compiler was made with. */
    private record Settings(
            SectionCompiler compiler,
            boolean ambientOcclusion,
            boolean cutoutLeaves,
            BlockStateModelSet models,
            FluidStateModelSet fluids,
            BlockColors colors) {}

    private static volatile @Nullable Settings settings;
    private static @Nullable Settings tablesFor;
    private static volatile @Nullable MeshTables tables;
    /** The animated sprite ids' generation the tables were made with (ADR 0030). */
    private static volatile int tablesGeneration;

    private static volatile boolean failed;
    private static boolean warnedUnsupported;

    private static @Nullable WorkerPool pool;
    private static boolean poolStarted;
    private static @Nullable MeshTables tablesSent;
    /** The current tables as the workers' message, encoded once (or read from the last start), until sent. */
    private static @Nullable ByteBuffer tablesMessage;

    /** One thread's meshing state: the snapshot, the mesher over the current tables, its output. */
    private static final class Local {
        final SnapshotBuilder builder = new SnapshotBuilder();
        final Snapshot snapshot = new Snapshot();
        final MeshResult result = new MeshResult();
        final Bytes.Out encoded = new Bytes.Out(128 * 1024);

        @Nullable
        SectionMesher mesher;
    }

    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);

    private Meshing() {}

    public static Mode mode() {
        return mode;
    }

    /**
     * Sets how sections are meshed, and whether in the platform's workers. Call before the game
     * starts: the section buffers are sized for the mode when they're made.
     */
    public static void configure(Mode newMode, boolean workers) {
        mode = newMode;
        useWorkers = workers;
        LOGGER.info("Sections are meshed by {}{}", describe(newMode), workers ? ", in the platform's workers" : "");
    }

    /**
     * Whether translucent layers whose order holds from every camera are written in it and never
     * re-sorted (the mesher's {@code FixedOrder}). Only in {@link Mode#INCARI}: the comparison with
     * the game's compiler wants the game's order. Off unless the page turns it on.
     */
    public static void setFixedOrder(boolean on) {
        fixedOrder = on;
    }

    /**
     * Whether snapshots read each section's copied blocks directly, rather than a block at a time
     * through the game's region (ADR 0034). On by default; the page's {@code ?snapshotreads=game}
     * turns it off, to compare.
     */
    public static void setDirectReads(boolean on) {
        SnapshotBuilder.setDirectReads(on);
    }

    /** In {@link Mode#VERIFY}: the sections compared so far, and how many differed. */
    public static int[] verifyCounts() {
        return MeshVerifier.counts();
    }

    /** Meshes sent to workers and not yet back, for the profiler's mesh queue. */
    public static int inFlight() {
        WorkerPool p = pool;
        return p == null ? 0 : p.inFlight();
    }

    private static String describe(Mode m) {
        return switch (m) {
            case VANILLA -> "the game's compiler";
            case INCARI -> "Incari's mesher";
            case VERIFY -> "the game's compiler, checked against Incari's mesher";
        };
    }

    private static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown incari.mesher mode {}; using the game's compiler", value);
            return Mode.VANILLA;
        }
    }

    /**
     * Hook: the starting size of a section buffer pack's buffer for a layer. Incari's mesher only
     * copies finished meshes into them, so they start small and grow to the largest; the game's
     * compiler builds its meshes in them, so it gets the sizes it asks for.
     */
    public static int sectionBufferSize(int vanillaSize) {
        return mode == Mode.INCARI ? Math.min(vanillaSize, SMALL_SECTION_BUFFER) : vanillaSize;
    }

    /** Hook: a section compiler was made with these models and settings. */
    public static void compilerCreated(
            SectionCompiler compiler,
            boolean ambientOcclusion,
            boolean cutoutLeaves,
            BlockStateModelSet models,
            FluidStateModelSet fluids,
            BlockColors colors) {
        settings = new Settings(compiler, ambientOcclusion, cutoutLeaves, models, fluids, colors);
    }

    /**
     * Hook: meshes a section, the game's section dispatcher's compile step.
     *
     * @param camera the camera, which translucent geometry is sorted from
     * @param sorting the game's sorting from that camera, for its own compiler
     */
    public static SectionCompiler.Results compile(
            SectionCompiler compiler,
            SectionPos section,
            RenderSectionRegion region,
            Vec3 camera,
            VertexSorting sorting,
            SectionBufferBuilderPack buffers) {
        Mode current = mode;
        boolean compact = TerrainVertexFormat.compact();
        if (current == Mode.VANILLA || (failed && !compact)) {
            return compiler.compile(section, region, sorting, buffers);
        }
        Settings s = settings;
        MeshTables t = tablesFor(compiler);
        if (t == null || s == null) {
            // The game's compiler can't write the compact format: without tables there's no mesh.
            return compact ? new SectionCompiler.Results() : compiler.compile(section, region, sorting, buffers);
        }
        Local local = LOCAL.get();
        List<BlockEntity> blockEntities = new ArrayList<>();
        int format = compact ? Snapshot.FORMAT_COMPACT : Snapshot.FORMAT_VANILLA;
        boolean supported = local.builder.fill(
                local.snapshot, t, s.colors(), s.fluids(), region, section, camera, format, blockEntities);
        if (!supported && !compact) {
            return compiler.compile(section, region, sorting, buffers);
        }
        if (!supported && !warnedUnsupported) {
            warnedUnsupported = true;
            LOGGER.warn("Section {} holds blocks Incari's mesher can't describe; they're left out", section);
        }
        local.snapshot.fixedOrder = fixedOrder && current == Mode.INCARI;
        if (local.builder.empty) {
            SectionCompiler.Results results = new SectionCompiler.Results();
            results.blockEntities.addAll(blockEntities);
            return results;
        }
        if (current == Mode.VERIFY) {
            mesher(local, t).mesh(local.snapshot, local.result);
            SectionCompiler.Results vanilla = compiler.compile(section, region, sorting, buffers);
            MeshVerifier.compare(section, region, vanilla, local.result, blockEntities);
            return vanilla;
        }
        WorkerPool workers = workers();
        if (workers != null && !RenderSystem.isOnRenderThread()) {
            try {
                return inWorker(workers, t, local, buffers, blockEntities);
            } catch (WorkerException e) {
                disableWorkers(e);
            }
        }
        mesher(local, t).mesh(local.snapshot, local.result);
        local.encoded.clear();
        local.result.encode(local.encoded);
        return MeshReply.results(wrap(local.encoded), buffers, blockEntities, tablesGeneration);
    }

    private static SectionCompiler.Results inWorker(
            WorkerPool workers,
            MeshTables t,
            Local local,
            SectionBufferBuilderPack buffers,
            List<BlockEntity> blockEntities) {
        sendTables(workers, t);
        local.encoded.clear();
        local.encoded.putInt(MeshJobs.MESH);
        local.snapshot.encode(local.encoded);
        WorkerJob job = workers.submit(wrap(local.encoded));
        try {
            ByteBuffer reply = job.await();
            int status = reply.getInt(0);
            if (status != MeshJobs.OK && status != MeshJobs.UNSUPPORTED) {
                throw new WorkerException(failure(reply, status));
            }
            return MeshReply.results(reply.position(4), buffers, blockEntities, tablesGeneration);
        } finally {
            job.release();
        }
    }

    /**
     * Hook: sorts a translucent layer again for a camera that moved, the game's re-sort step.
     *
     * @return the sorted index buffer, built in {@code target}
     */
    public static ByteBufferBuilder.@Nullable Result sortedIndices(
            MeshData.SortState state, ByteBufferBuilder target, VertexSorting sorting, Vec3 camera, long sectionNode) {
        WorkerPool workers = mode == Mode.INCARI ? workers() : null;
        MeshTables t = tables;
        if (workers == null || t == null || RenderSystem.isOnRenderThread()) {
            return state.buildSortedIndexBuffer(target, sorting);
        }
        try {
            return sortInWorker(workers, t, state, target, camera, sectionNode);
        } catch (WorkerException e) {
            disableWorkers(e);
            return state.buildSortedIndexBuffer(target, sorting);
        }
    }

    private static ByteBufferBuilder.@Nullable Result sortInWorker(
            WorkerPool workers,
            MeshTables t,
            MeshData.SortState state,
            ByteBufferBuilder target,
            Vec3 camera,
            long sectionNode) {
        Local local = LOCAL.get();
        sendTables(workers, t);
        CompactVectorArray centroids = state.centroids();
        int count = centroids.size();
        float[] values = new float[count * 3];
        for (int i = 0; i < count; i++) {
            values[i * 3] = centroids.getX(i);
            values[i * 3 + 1] = centroids.getY(i);
            values[i * 3 + 2] = centroids.getZ(i);
        }
        SectionPos section = SectionPos.of(sectionNode);
        local.encoded.clear();
        MeshJobs.encodeSort(
                local.encoded,
                values,
                count,
                (float) (camera.x - section.minBlockX()),
                (float) (camera.y - section.minBlockY()),
                (float) (camera.z - section.minBlockZ()),
                state.indexType() == IndexType.INT);
        WorkerJob job = workers.submit(wrap(local.encoded));
        try {
            ByteBuffer reply = job.await();
            int status = reply.getInt(0);
            if (status != MeshJobs.OK) {
                throw new WorkerException(failure(reply, status));
            }
            int length = reply.getInt(4);
            if (length == 0) {
                return null;
            }
            target.reserve(length);
            ByteBufferBuilder.Result sorted = target.build();
            if (sorted != null) {
                sorted.byteBuffer().put(0, reply, 8, length);
            }
            return sorted;
        } finally {
            job.release();
        }
    }

    /** Sends the tables to the workers if they don't have these yet; every job after reads them. */
    private static void sendTables(WorkerPool workers, MeshTables t) {
        synchronized (Meshing.class) {
            if (tablesSent == t) {
                return;
            }
            ByteBuffer message = tablesMessage;
            if (message == null || tables != t) {
                message = message(t);
            }
            workers.broadcast(message.duplicate());
            tablesSent = t;
            // The workers have their copy; the page needs it no more (11 MB of heap).
            tablesMessage = null;
            LOGGER.info("Mesher tables sent to {} workers ({} KB)", workers.size(), message.remaining() / 1024);
        }
    }

    /** The workers' message carrying these tables. */
    private static ByteBuffer message(MeshTables t) {
        Bytes.Out out = new Bytes.Out(8 * 1024 * 1024);
        out.putInt(MeshJobs.TABLES);
        t.encode(out);
        return ByteBuffer.wrap(out.array(), 0, out.size()).slice();
    }

    private static String failure(ByteBuffer reply, int status) {
        if (status == MeshJobs.FAILED) {
            int length = reply.getInt(4);
            byte[] text = new byte[length];
            reply.get(8, text);
            return "the mesher failed: " + new String(text, java.nio.charset.StandardCharsets.US_ASCII);
        }
        return "the mesher answered " + status;
    }

    private static @Nullable WorkerPool workers() {
        if (!useWorkers) {
            return null;
        }
        synchronized (Meshing.class) {
            if (!poolStarted) {
                poolStarted = true;
                pool = Platforms.current().threads().workers("mesher");
                if (pool == null) {
                    LOGGER.info("No mesher workers here; sections are meshed on the game's threads");
                } else {
                    LOGGER.info("Meshing in {} workers", pool.size());
                }
            }
            return pool;
        }
    }

    private static void disableWorkers(WorkerException e) {
        synchronized (Meshing.class) {
            if (useWorkers) {
                LOGGER.error("The mesher workers failed; meshing on the game's threads from now on", e);
                useWorkers = false;
            }
        }
    }

    private static SectionMesher mesher(Local local, MeshTables t) {
        SectionMesher mesher = local.mesher;
        if (mesher == null || mesher.tables() != t) {
            mesher = new SectionMesher(t);
            local.mesher = mesher;
        }
        return mesher;
    }

    private static ByteBuffer wrap(Bytes.Out out) {
        return ByteBuffer.wrap(out.array(), 0, out.size()).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The tables for this compiler's models and settings, built the first time they're needed. */
    @SuppressWarnings("ReferenceEquality") // the compiler and settings objects themselves
    private static @Nullable MeshTables tablesFor(SectionCompiler compiler) {
        Settings s = settings;
        if (s == null || s.compiler() != compiler) {
            return null;
        }
        MeshTables t = tables;
        if (t != null && tablesFor == s) {
            return t;
        }
        synchronized (Meshing.class) {
            if (tablesFor != s && !failed) {
                try {
                    // Read first: if the atlas is rebuilt while the tables are, they're the older ones.
                    int generation = AnimatedSprites.generation();
                    MeshTableCache.Kept kept = MeshTableCache.load(s.ambientOcclusion(), s.cutoutLeaves());
                    if (kept != null) {
                        MeshTableExport.checkArithmetic();
                        tablesMessage = kept.message();
                        tables = kept.tables();
                    } else {
                        MeshTables built =
                                MeshTableExport.build(s.ambientOcclusion(), s.cutoutLeaves(), s.models(), s.fluids());
                        ByteBuffer message = message(built);
                        MeshTableCache.store(s.ambientOcclusion(), s.cutoutLeaves(), message);
                        tablesMessage = message;
                        tables = built;
                    }
                    tablesGeneration = generation;
                    tablesFor = s;
                } catch (RuntimeException e) {
                    LOGGER.error("Incari's mesher can't describe this game's blocks; the game meshes them", e);
                    failed = true;
                    return null;
                }
            }
            return failed ? null : tables;
        }
    }
}
