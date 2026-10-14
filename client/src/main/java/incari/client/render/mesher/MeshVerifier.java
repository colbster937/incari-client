package incari.client.render.mesher;

import com.mojang.blaze3d.vertex.MeshData;
import incari.mesher.Facings;
import incari.mesher.MeshResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks Incari's mesher against the game's section compiler: with {@code
 * -Dincari.mesher=verify} on the desktop, every section is meshed both ways and the two compared
 * byte for byte, in the game's own vertex format: every layer's vertices in order, the translucent
 * layer's sorted indices, the visibility set, the block entities. Differences are logged with the
 * vertex and block they're at; the game draws its own mesh either way.
 *
 * <p>Incari's mesher writes the solid and cutout quads grouped by facing (ADR 0030), so the game's
 * quads of those layers are grouped the same way, by the same rule, before they're compared, and the
 * runs' lengths are compared too.
 */
final class MeshVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeshVerifier.class);
    private static final int MAX_REPORTS = 40;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final AtomicInteger compared = new AtomicInteger();
    private static final AtomicInteger differed = new AtomicInteger();
    private static final AtomicInteger reports = new AtomicInteger();

    private MeshVerifier() {}

    /** The sections compared so far, and how many of them differed. */
    static int[] counts() {
        return new int[] {compared.get(), differed.get()};
    }

    static void compare(
            SectionPos section,
            RenderSectionRegion region,
            SectionCompiler.Results vanilla,
            MeshResult incari,
            List<BlockEntity> blockEntities) {
        StringBuilder problems = new StringBuilder();
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            MeshData mesh = vanilla.renderedLayers.get(layer);
            int ours = incari.vertexCount(Layers.index(layer));
            int theirs = mesh == null ? 0 : mesh.drawState().vertexCount();
            if (ours != theirs) {
                problems.append(String.format(
                        Locale.ROOT, "%n  %s: %d vertices, the game has %d", layer.label(), ours, theirs));
            }
            if (mesh == null || ours == 0) {
                continue;
            }
            int size = layer.vertexFormat().getVertexSize();
            ByteBuffer expected = mesh.vertexBuffer().order(ByteOrder.LITTLE_ENDIAN);
            if (!layer.translucent()) {
                int[] runs = new int[Facings.COUNT];
                expected = groupedByFacing(expected, theirs, size, runs);
                for (int run = 0; run < Facings.COUNT; run++) {
                    if (runs[run] != incari.facingRun(Layers.index(layer), run)) {
                        problems.append(String.format(
                                Locale.ROOT,
                                "%n  %s: %d quads facing %d, the game has %d",
                                layer.label(),
                                incari.facingRun(Layers.index(layer), run),
                                Facings.facingOfRun(run),
                                runs[run]));
                    }
                }
            }
            byte[] actual = incari.vertexBytes(Layers.index(layer));
            int vertices = Math.min(ours, theirs);
            int shown = 0;
            for (int v = 0; v < vertices && shown < 3; v++) {
                if (!sameVertex(expected, actual, v * size, size)) {
                    problems.append(String.format(
                            Locale.ROOT,
                            "%n  %s vertex %d (quad %d): ours %s, the game's %s%s",
                            layer.label(),
                            v,
                            v / 4,
                            describe(ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN), v * size),
                            describe(expected, v * size),
                            blockAt(section, region, expected, v * size)));
                    shown++;
                }
            }
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                ByteBuffer indices = mesh.indexBuffer();
                if (indices == null) {
                    problems.append("\n  translucent: the game has no sorted indices");
                } else {
                    byte[] ourIndices = incari.sortedIndexBytes();
                    int count = incari.sortedIndexByteCount();
                    if (count != indices.remaining()) {
                        problems.append(String.format(
                                Locale.ROOT,
                                "%n  translucent: %d index bytes, the game has %d",
                                count,
                                indices.remaining()));
                    } else {
                        for (int i = 0; i < count; i++) {
                            if (ourIndices[i] != indices.get(indices.position() + i)) {
                                problems.append(String.format(
                                        Locale.ROOT, "%n  translucent: sorted indices differ from byte %d", i));
                                break;
                            }
                        }
                    }
                }
            }
        }
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 6; b++) {
                boolean ours = (incari.visibility & 1L << (a + 6 * b)) != 0;
                boolean theirs = vanilla.visibilitySet.visibilityBetween(DIRECTIONS[a], DIRECTIONS[b]);
                if (ours != theirs) {
                    problems.append(String.format(
                            Locale.ROOT,
                            "%n  visibility %s-%s: ours %b, the game's %b",
                            DIRECTIONS[a],
                            DIRECTIONS[b],
                            ours,
                            theirs));
                }
            }
        }
        if (!blockEntities.equals(vanilla.blockEntities)) {
            problems.append(String.format(
                    Locale.ROOT, "%n  block entities: ours %s, the game's %s", blockEntities, vanilla.blockEntities));
        }
        int total = compared.incrementAndGet();
        if (!problems.isEmpty()) {
            int count = differed.incrementAndGet();
            if (reports.incrementAndGet() <= MAX_REPORTS) {
                LOGGER.warn(
                        "Mesher differs from the game at section {} {} {}:{}",
                        section.x(),
                        section.y(),
                        section.z(),
                        problems);
            }
            if (count % 100 == 0) {
                LOGGER.warn("Mesher: {} of {} sections differ", count, total);
            }
        }
        if (total % 1000 == 0) {
            LOGGER.info("Mesher verified {} sections, {} differ", total, differed.get());
        }
    }

    /**
     * A copy of the game's vertices (from {@code vertices}' position) with its quads grouped by
     * facing as Incari's mesher groups them, counting each run into {@code runs}.
     */
    private static ByteBuffer groupedByFacing(ByteBuffer vertices, int vertexCount, int size, int[] runs) {
        int quads = vertexCount / 4;
        int quadBytes = size * 4;
        int base = vertices.position();
        int[] facing = new int[quads];
        for (int q = 0; q < quads; q++) {
            int at = base + q * quadBytes;
            facing[q] = Facings.runOf(Facings.of(
                    vertices.getFloat(at),
                    vertices.getFloat(at + 4),
                    vertices.getFloat(at + 8),
                    vertices.getFloat(at + size),
                    vertices.getFloat(at + size + 4),
                    vertices.getFloat(at + size + 8),
                    vertices.getFloat(at + 2 * size),
                    vertices.getFloat(at + 2 * size + 4),
                    vertices.getFloat(at + 2 * size + 8),
                    vertices.getFloat(at + 3 * size),
                    vertices.getFloat(at + 3 * size + 4),
                    vertices.getFloat(at + 3 * size + 8)));
            runs[facing[q]]++;
        }
        int[] next = new int[Facings.COUNT];
        for (int run = 1; run < Facings.COUNT; run++) {
            next[run] = next[run - 1] + runs[run - 1];
        }
        ByteBuffer grouped = ByteBuffer.allocate(quads * quadBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < quads; q++) {
            grouped.put(next[facing[q]]++ * quadBytes, vertices, base + q * quadBytes, quadBytes);
        }
        return grouped;
    }

    private static boolean sameVertex(ByteBuffer expected, byte[] actual, int at, int size) {
        for (int i = 0; i < size; i++) {
            if (expected.get(expected.position() + at + i) != actual[at + i]) {
                return false;
            }
        }
        return true;
    }

    /** A game-format vertex: position, color, texture coordinates, light. */
    private static String describe(ByteBuffer buffer, int at) {
        int base = buffer.position() + at;
        return String.format(
                Locale.ROOT,
                "(%.5f %.5f %.5f #%08x %.6f %.6f light %d/%d)",
                buffer.getFloat(base),
                buffer.getFloat(base + 4),
                buffer.getFloat(base + 8),
                buffer.getInt(base + 12),
                buffer.getFloat(base + 16),
                buffer.getFloat(base + 20),
                buffer.getShort(base + 24) & 0xFFFF,
                buffer.getShort(base + 26) & 0xFFFF);
    }

    private static String blockAt(SectionPos section, RenderSectionRegion region, ByteBuffer buffer, int at) {
        int base = buffer.position() + at;
        BlockPos pos = BlockPos.containing(
                section.minBlockX() + buffer.getFloat(base) - 0.001,
                section.minBlockY() + buffer.getFloat(base + 4) - 0.001,
                section.minBlockZ() + buffer.getFloat(base + 8) - 0.001);
        return " near " + pos.toShortString() + " " + region.getBlockState(pos);
    }
}
