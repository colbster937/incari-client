package incari.client.render.mesher;

import incari.mesher.Bytes;
import incari.mesher.MeshJobs;
import incari.mesher.MeshTables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mesher's tables kept from one start to the next (ADR 0032). Building them reads every block
 * state and baked model of the game: 7 s of the floor stand-in's 23 s join, every join after every
 * start, for the same tables. Here the tables are kept in a file (the page's file system writes it
 * through to OPFS) under a key naming everything they're made from, and read back while the key is
 * the same.
 *
 * <p>The key: the build (its id, a hash of its files, so other Incari code or another game
 * version never reads them), the two settings the tables depend on (smooth lighting, cutout
 * leaves), the selected resource packs, and every blocks atlas sprite's name, place, size, texture
 * coordinates and animation id, which is everything of the atlas the baked quads hold. Models come
 * from the packs; the tables are only kept while every pack is one the build has (vanilla and the
 * game's built-in packs), whose files the build id names. A pack of the player's or a server's
 * isn't named by anything cheap enough to check, so with one selected the tables are built as
 * before and the file is left alone.
 *
 * <p>The file: {@value #MAGIC} and the format version as ints, the key's length and bytes, then the
 * workers' tables message (as {@code Meshing} broadcasts it) with its length. It's in the game's
 * heap while the page runs (the file system's tree holds it, about 11 MB). Off unless the page
 * turns it on ({@code ?meshtables=build} leaves it off); the desktop builds its tables every time.
 */
public final class MeshTableCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeshTableCache.class);

    static final int MAGIC = 0x494d5431; // "IMT1"
    static final int VERSION = 1;

    private static @Nullable Path file;
    private static @Nullable String build;
    private static byte @Nullable [] atlasKey;

    /** Tables just read or built, and the workers' message they were read from or encoded into. */
    record Kept(MeshTables tables, ByteBuffer message) {}

    private MeshTableCache() {}

    /** Keeps the tables in {@code at}, for the build {@code buildId} (the entry point). */
    public static synchronized void enable(Path at, String buildId) {
        file = at;
        build = buildId;
    }

    /**
     * From {@code AnimatedSprites.atlasBuilt}: the blocks atlas was built with these sprites, their
     * animation ids given.
     */
    public static synchronized void atlasBuilt(List<TextureAtlasSprite> sprites) {
        if (file == null) {
            return;
        }
        Bytes.Out out = new Bytes.Out(64 * 1024);
        out.putInt(sprites.size());
        for (TextureAtlasSprite sprite : sprites) {
            putString(out, sprite.contents().name().toString());
            out.putInt(sprite.getX());
            out.putInt(sprite.getY());
            out.putInt(sprite.contents().width());
            out.putInt(sprite.contents().height());
            out.putFloat(sprite.getU0());
            out.putFloat(sprite.getU1());
            out.putFloat(sprite.getV0());
            out.putFloat(sprite.getV1());
            out.putInt(sprite.incariAnimatedId);
        }
        atlasKey = out.toArray();
    }

    /** The kept tables for these settings and the game's packs and atlas now, or null. */
    static synchronized @Nullable Kept load(boolean ambientOcclusion, boolean cutoutLeaves) {
        Path at = file;
        byte[] key = key(ambientOcclusion, cutoutLeaves);
        if (at == null || key == null) {
            return null;
        }
        long start = System.nanoTime();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(at);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOGGER.warn("Can't read the kept mesher tables; building them", e);
            return null;
        }
        Bytes.In in = new Bytes.In(bytes, 0, bytes.length);
        try {
            if (in.getInt() != MAGIC || in.getInt() != VERSION) {
                return null;
            }
            byte[] keptKey = in.getByteArray();
            if (!Arrays.equals(keptKey, key)) {
                LOGGER.info("The kept mesher tables are for other packs, settings or another build; building them");
                return null;
            }
            int length = in.getInt();
            int offset = in.position();
            if (length != bytes.length - offset) {
                return null;
            }
            Bytes.In message = new Bytes.In(bytes, offset, length);
            if (message.getInt() != MeshJobs.TABLES) {
                return null;
            }
            MeshTables tables = MeshTables.decode(message);
            LOGGER.info(
                    "Mesher tables read from the last start ({} KB) in {} ms",
                    length / 1024,
                    (System.nanoTime() - start) / 1_000_000);
            return new Kept(tables, ByteBuffer.wrap(bytes, offset, length).slice());
        } catch (RuntimeException e) {
            LOGGER.warn("The kept mesher tables don't read; building them", e);
            return null;
        }
    }

    /** Keeps tables just built, as encoded into the workers' {@code message}, if they can be kept. */
    static synchronized void store(boolean ambientOcclusion, boolean cutoutLeaves, ByteBuffer message) {
        Path at = file;
        byte[] key = key(ambientOcclusion, cutoutLeaves);
        if (at == null || key == null) {
            return;
        }
        Bytes.Out out = new Bytes.Out(message.remaining() + key.length + 32);
        out.putInt(MAGIC);
        out.putInt(VERSION);
        out.putByteArray(key, key.length);
        out.putInt(message.remaining());
        int offset = out.reserve(message.remaining());
        message.duplicate().get(out.array(), offset, message.remaining());
        try {
            Files.createDirectories(at.getParent());
            Files.write(at, Arrays.copyOf(out.array(), out.size()));
        } catch (IOException e) {
            LOGGER.warn("Can't keep the mesher tables", e);
        }
    }

    /** Everything the tables are made from, or null if something of it can't be named. */
    @SuppressWarnings("ReferenceEquality") // the pack sources are the game's constants
    private static byte @Nullable [] key(boolean ambientOcclusion, boolean cutoutLeaves) {
        String id = build;
        byte[] atlas = atlasKey;
        if (id == null || atlas == null) {
            return null;
        }
        Bytes.Out out = new Bytes.Out(atlas.length + 1024);
        putString(out, id);
        out.putByte(ambientOcclusion ? 1 : 0);
        out.putByte(cutoutLeaves ? 1 : 0);
        for (Pack pack : Minecraft.getInstance().getResourcePackRepository().getSelectedPacks()) {
            if (pack.location().source() != PackSource.BUILT_IN) {
                return null;
            }
            putString(out, pack.getId());
        }
        out.putBytes(atlas, 0, atlas.length);
        return out.toArray();
    }

    private static void putString(Bytes.Out out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putByteArray(bytes, bytes.length);
    }
}
