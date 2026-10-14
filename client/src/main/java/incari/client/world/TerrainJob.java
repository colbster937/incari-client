package incari.client.world;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.jspecify.annotations.Nullable;

/**
 * A chunk's terrain step (noise, surface, carvers: 26.3's {@code buildTerrain}) as a helper worker is
 * given it and what it gives back ({@link RemoteTerrain}, ADR 0042). The step reads nothing of the
 * world but the seed's noise, the biomes of the chunk and the eight around it, and the structure
 * pieces near it (the {@link Beardifier} the server works out), and writes nothing but the chunk's
 * blocks, its heightmaps and its fluids to update later: those cross as bytes.
 *
 * <p>Biomes and blocks cross in the game's own section format, palette and all, so the chunk the
 * server ends up with is the one the helper made, down to its palettes' order, and is saved the
 * same. Registry ids are the server's and the helper's alike: the helper loads the same data packs
 * and refuses the world if its biomes are numbered otherwise.
 */
// Enums cross by ordinal: the server and its helpers run the same build, so they number them alike.
@SuppressWarnings("EnumOrdinal")
public final class TerrainJob {

    private TerrainJob() {}

    // --- The job: the server's side ------------------------------------------------------------

    /**
     * The job for {@code chunk}: its biomes and its neighbours' ({@code chunks} answers for the 3x3
     * around it), and what of the structures near it shapes its terrain.
     */
    public static byte[] encode(
            int id,
            int generator,
            ChunkAccess chunk,
            BiFunction<Integer, Integer, ChunkAccess> chunks,
            Beardifier beardifier) {
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(16 * 1024));
        ChunkPos pos = chunk.getPos();
        out.writeVarInt(id);
        out.writeVarInt(generator);
        out.writeInt(pos.x());
        out.writeInt(pos.z());
        out.writeVarInt(BuiltInRegistries.CHUNK_STATUS.getId(chunk.getPersistedStatus()));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkAccess around = dx == 0 && dz == 0 ? chunk : chunks.apply(pos.x() + dx, pos.z() + dz);
                for (LevelChunkSection section : around.getSections()) {
                    section.getBiomes().write(out);
                }
            }
        }
        List<Beardifier.Rigid> pieces = beardifier.pieces();
        out.writeVarInt(pieces.size());
        for (Beardifier.Rigid piece : pieces) {
            writeBox(out, piece.box());
            out.writeVarInt(piece.terrainAdjustment().ordinal());
            out.writeVarInt(piece.groundLevelDelta());
        }
        List<JigsawJunction> junctions = beardifier.junctions();
        out.writeVarInt(junctions.size());
        for (JigsawJunction junction : junctions) {
            out.writeInt(junction.getSourceX());
            out.writeInt(junction.getSourceGroundY());
            out.writeInt(junction.getSourceZ());
            out.writeInt(junction.getDeltaY());
            out.writeVarInt(junction.getDestProjection().ordinal());
        }
        BoundingBox affected = beardifier.affectedBox();
        out.writeBoolean(affected != null);
        if (affected != null) {
            writeBox(out, affected);
        }
        return bytes(out);
    }

    // --- The job: the helper's side ------------------------------------------------------------

    /** A job as the helper reads it: the chunk to build, with its biomes, and what it needs around it. */
    public record Job(
            int id,
            int generator,
            ProtoChunk chunk,
            Map<ChunkPos, ProtoChunk> around,
            Set<Holder<Biome>> possibleBiomes,
            Beardifier beardifier) {

        /** The biomes as the server's region gives them for the surface: the 3x3 chunks' own. */
        public BiomeResolver biomes(BiomeResolver beyond) {
            return (quartX, quartY, quartZ) -> {
                ProtoChunk chunk = around.get(new ChunkPos(quartX >> 2, quartZ >> 2));
                return chunk != null
                        ? chunk.getNoiseBiome(quartX, quartY, quartZ)
                        : beyond.getNoiseBiome(quartX, quartY, quartZ);
            };
        }
    }

    /** Reads a job, making the chunk and its neighbours as the server had them, biomes and all. */
    public static Job decode(byte[] bytes, LevelHeightAccessor height, PalettedContainerFactory containers) {
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        int id = in.readVarInt();
        int generator = in.readVarInt();
        int chunkX = in.readInt();
        int chunkZ = in.readInt();
        ChunkStatus status = BuiltInRegistries.CHUNK_STATUS.byIdOrThrow(in.readVarInt());
        Map<ChunkPos, ProtoChunk> around = new java.util.HashMap<>();
        Set<Holder<Biome>> possibleBiomes = new HashSet<>();
        ProtoChunk center = null;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ProtoChunk chunk = new ProtoChunk(
                        new ChunkPos(chunkX + dx, chunkZ + dz), UpgradeData.EMPTY, height, containers, null);
                for (LevelChunkSection section : chunk.getSections()) {
                    section.readBiomes(in);
                }
                chunk.setPersistedStatus(status);
                // The server's set: every biome in the 3x3 chunks' palettes.
                chunk.collectBiomesInPalette(possibleBiomes);
                around.put(chunk.getPos(), chunk);
                if (dx == 0 && dz == 0) {
                    center = chunk;
                }
            }
        }
        int pieceCount = in.readVarInt();
        List<Beardifier.Rigid> pieces = new ArrayList<>(pieceCount);
        for (int i = 0; i < pieceCount; i++) {
            BoundingBox box = readBox(in);
            TerrainAdjustment adjustment = TerrainAdjustment.values()[in.readVarInt()];
            pieces.add(new Beardifier.Rigid(box, adjustment, in.readVarInt()));
        }
        int junctionCount = in.readVarInt();
        List<JigsawJunction> junctions = new ArrayList<>(junctionCount);
        for (int i = 0; i < junctionCount; i++) {
            int x = in.readInt();
            int groundY = in.readInt();
            int z = in.readInt();
            int deltaY = in.readInt();
            StructureTemplatePool.Projection projection = StructureTemplatePool.Projection.values()[in.readVarInt()];
            junctions.add(new JigsawJunction(x, groundY, z, deltaY, projection));
        }
        BoundingBox affected = in.readBoolean() ? readBox(in) : null;
        // The game's own empty one where there's nothing, as the server's would be.
        Beardifier beardifier = pieces.isEmpty() && junctions.isEmpty() && affected == null
                ? Beardifier.EMPTY
                : new Beardifier(List.copyOf(pieces), List.copyOf(junctions), affected);
        if (center == null) {
            throw new IllegalStateException("No chunk in the job");
        }
        return new Job(id, generator, center, Map.copyOf(around), possibleBiomes, beardifier);
    }

    // --- The result ----------------------------------------------------------------------------

    /** What the step made of {@code chunk}: its blocks, heightmaps and fluids to update. Helper's side. */
    public static byte[] result(int id, ChunkAccess chunk) {
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(64 * 1024));
        out.writeVarInt(id);
        LevelChunkSection[] sections = chunk.getSections();
        out.writeVarInt(sections.length);
        for (LevelChunkSection section : sections) {
            section.getStates().write(out);
        }
        List<Map.Entry<Heightmap.Types, Heightmap>> heightmaps = new ArrayList<>(chunk.getHeightmaps());
        out.writeVarInt(heightmaps.size());
        for (Map.Entry<Heightmap.Types, Heightmap> heightmap : heightmaps) {
            out.writeVarInt(heightmap.getKey().ordinal());
            out.writeLongArray(heightmap.getValue().getRawData());
        }
        @Nullable ShortList[] postProcessing = chunk.getPostProcessing();
        int lists = 0;
        for (ShortList list : postProcessing) {
            if (list != null && !list.isEmpty()) {
                lists++;
            }
        }
        out.writeVarInt(lists);
        for (int i = 0; i < postProcessing.length; i++) {
            ShortList list = postProcessing[i];
            if (list != null && !list.isEmpty()) {
                out.writeVarInt(i);
                out.writeVarInt(list.size());
                for (int j = 0; j < list.size(); j++) {
                    out.writeShort(list.getShort(j));
                }
            }
        }
        return bytes(out);
    }

    /** The job a result answers. */
    public static int resultId(byte[] bytes) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)).readVarInt();
    }

    /**
     * Puts a result into the server's chunk, which the step hasn't touched (all air, no heightmaps
     * and nothing to update yet: {@link RemoteTerrain} sends no other), as the step would have left
     * it. Server's side.
     */
    public static void apply(byte[] bytes, ChunkAccess chunk) {
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        in.readVarInt();
        LevelChunkSection[] sections = chunk.getSections();
        int count = in.readVarInt();
        if (count != sections.length) {
            throw new IllegalStateException("A result of " + count + " sections for a chunk of " + sections.length);
        }
        for (LevelChunkSection section : sections) {
            section.getStates().read(in);
            section.recalcBlockCounts();
        }
        int heightmaps = in.readVarInt();
        for (int i = 0; i < heightmaps; i++) {
            Heightmap.Types type = Heightmap.Types.values()[in.readVarInt()];
            chunk.setHeightmap(type, in.readLongArray());
        }
        int lists = in.readVarInt();
        for (int i = 0; i < lists; i++) {
            int sectionIndex = in.readVarInt();
            int size = in.readVarInt();
            ShortList list = new ShortArrayList(size);
            for (int j = 0; j < size; j++) {
                list.add(in.readShort());
            }
            chunk.addPackedPostProcess(list, sectionIndex);
        }
    }

    private static void writeBox(FriendlyByteBuf out, BoundingBox box) {
        out.writeInt(box.minX());
        out.writeInt(box.minY());
        out.writeInt(box.minZ());
        out.writeInt(box.maxX());
        out.writeInt(box.maxY());
        out.writeInt(box.maxZ());
    }

    private static BoundingBox readBox(FriendlyByteBuf in) {
        return new BoundingBox(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static byte[] bytes(FriendlyByteBuf out) {
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        return bytes;
    }
}
