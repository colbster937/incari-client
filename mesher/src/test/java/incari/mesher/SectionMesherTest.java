package incari.mesher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The mesher on hand-made tables: what it writes for a lone cube, how faces between cubes are
 * culled, and that its formats survive the trip to a worker and back. Its agreement with the game's
 * own compiler is checked against the game itself ({@code ./gradlew :app-desktop:smokeMesher}).
 */
class SectionMesherTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int GLASS = 2;
    private static final int SHEET = 3;

    /**
     * Four states: air, a stone cube (culls against anything full), a glass cube (translucent), and
     * a translucent sheet: only a cube's top and bottom, as a still water surface seen both ways.
     */
    private static MeshTables tables(boolean ambientOcclusion) {
        MeshTables.Builder b = new MeshTables.Builder(4, ambientOcclusion);
        b.state(AIR, StateFlags.AIR | StateFlags.LIGHT_PERMEABLE, 0, 1.0F);
        int[][] stoneFaces = new int[7][];
        int[][] glassFaces = new int[7][];
        int[][] sheetFaces = new int[7][];
        for (int d = 0; d < 6; d++) {
            stoneFaces[d] = new int[] {b.quad(face(d), uvs(), d, MeshTables.LAYER_SOLID, -1, -1, 0)};
            glassFaces[d] = new int[] {b.quad(face(d), uvs(), d, MeshTables.LAYER_TRANSLUCENT, -1, -1, 0)};
            sheetFaces[d] = d == Dirs.DOWN || d == Dirs.UP
                    ? new int[] {b.quad(face(d), uvs(), d, MeshTables.LAYER_TRANSLUCENT, -1, -1, 0)}
                    : new int[0];
        }
        stoneFaces[6] = new int[0];
        glassFaces[6] = new int[0];
        sheetFaces[6] = new int[0];
        int stoneNode = b.singleNode(b.part(stoneFaces, true));
        int glassNode = b.singleNode(b.part(glassFaces, true));
        int sheetNode = b.singleNode(b.part(sheetFaces, true));
        b.state(STONE, StateFlags.MODEL | StateFlags.SOLID_RENDER | StateFlags.COLLISION_FULL, 0, 0.2F);
        b.model(STONE, stoneNode);
        b.state(GLASS, StateFlags.MODEL | StateFlags.LIGHT_PERMEABLE | StateFlags.COLLISION_FULL, 0, 1.0F);
        b.model(GLASS, glassNode);
        b.state(SHEET, StateFlags.MODEL | StateFlags.LIGHT_PERMEABLE, 0, 1.0F);
        b.model(SHEET, sheetNode);
        for (int d = 0; d < 6; d++) {
            b.faceShape(STONE, d, MeshTables.SHAPE_FULL);
        }
        b.faceShapes(2, (x, y) -> y == MeshTables.SHAPE_FULL);
        return b.build();
    }

    /** A unit cube's face toward direction d, corners in the order the game's model baker uses. */
    private static float[] face(int d) {
        float[][] corners =
                switch (d) {
                    case Dirs.DOWN -> new float[][] {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
                    case Dirs.UP -> new float[][] {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
                    case Dirs.NORTH -> new float[][] {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}};
                    case Dirs.SOUTH -> new float[][] {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}};
                    case Dirs.WEST -> new float[][] {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}};
                    default -> new float[][] {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}};
                };
        float[] positions = new float[12];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(corners[v], 0, positions, v * 3, 3);
        }
        return positions;
    }

    private static float[] uvs() {
        return new float[] {0, 0, 0, 0.0625F, 0.0625F, 0.0625F, 0.0625F, 0};
    }

    private static Snapshot snapshot(int... blocks) {
        Snapshot s = new Snapshot();
        for (int i = 0; i < 6; i++) {
            s.cardinalLighting[i] = new float[] {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F}[i];
        }
        // Full sky light everywhere.
        java.util.Arrays.fill(s.light, (byte) 0xF0);
        for (int i = 0; i < blocks.length; i += 4) {
            s.states[Snapshot.index(blocks[i], blocks[i + 1], blocks[i + 2])] = (char) blocks[i + 3];
        }
        return s;
    }

    @Test
    void aLoneCubeHasSixLitFaces() {
        SectionMesher mesher = new SectionMesher(tables(false));
        MeshResult result = new MeshResult();
        assertThat(mesher.mesh(snapshot(5, 5, 5, STONE), result)).isTrue();

        assertThat(result.vertexCount(MeshTables.LAYER_SOLID)).isEqualTo(24);
        ByteBuffer vertices =
                ByteBuffer.wrap(result.vertexBytes(MeshTables.LAYER_SOLID)).order(ByteOrder.LITTLE_ENDIAN);
        // The down face first: its first corner at (5, 5, 6), shaded half, lit by the sky.
        assertThat(vertices.getFloat(0)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(4)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(8)).isEqualTo(6.0F);
        assertThat(vertices.getInt(12)).isEqualTo(0xFF7F7F7F);
        assertThat(vertices.getShort(26) & 0xFFFF).isEqualTo(240);
        // Then up, at full brightness.
        assertThat(vertices.getInt(4 * 28 + 12)).isEqualTo(0xFFFFFFFF);
    }

    @Test
    void facesBetweenCubesAreCulled() {
        SectionMesher mesher = new SectionMesher(tables(true));
        MeshResult result = new MeshResult();
        mesher.mesh(snapshot(5, 5, 5, STONE, 6, 5, 5, STONE), result);
        assertThat(result.vertexCount(MeshTables.LAYER_SOLID)).isEqualTo(4 * 10);
    }

    @Test
    void translucentQuadsAreSortedFarthestFirst() {
        SectionMesher mesher = new SectionMesher(tables(false));
        MeshResult result = new MeshResult();
        Snapshot s = snapshot(1, 1, 1, GLASS, 10, 1, 1, GLASS);
        s.cameraX = 0.0F;
        s.cameraY = 1.5F;
        s.cameraZ = 1.5F;
        mesher.mesh(s, result);
        assertThat(result.translucentQuadCount()).isEqualTo(12);
        ByteBuffer indices = ByteBuffer.wrap(result.sortedIndexBytes()).order(ByteOrder.LITTLE_ENDIAN);
        // The first quad drawn belongs to the far cube (its vertices come after the near one's 24).
        assertThat(indices.getShort(0) & 0xFFFF).isGreaterThanOrEqualTo(24);
        assertThat(indices.getShort((12 - 1) * 12) & 0xFFFF).isLessThan(24);
    }

    @Test
    void quadsAcrossOneAxisKeepAnOrderThatHoldsFromEveryCamera() {
        SectionMesher mesher = new SectionMesher(tables(false));
        MeshResult result = new MeshResult();
        // Two sheets in a column: tops at y 2 and 4, bottoms at y 1 and 3.
        Snapshot s = snapshot(1, 1, 1, SHEET, 1, 3, 1, SHEET);
        s.fixedOrder = true;
        s.cameraX = 8.0F;
        s.cameraY = 2.5F;
        s.cameraZ = 8.0F;
        mesher.mesh(s, result);
        assertThat(result.fixedOrder()).isTrue();
        // Quads facing down by their plane falling, quads facing up by their plane rising, wherever
        // the camera is: from y 2.5 it sees the bottom at 3 and the top at 2, drawn farthest first.
        assertThat(quadPlanesY(result)).containsExactly(3.0F, 1.0F, 2.0F, 4.0F);

        s.cameraY = 30.0F;
        mesher.mesh(s, result);
        assertThat(quadPlanesY(result)).containsExactly(3.0F, 1.0F, 2.0F, 4.0F);
    }

    @Test
    void quadsAcrossSeveralAxesOrWithoutTheSwitchAreSortedFromTheCamera() {
        SectionMesher mesher = new SectionMesher(tables(false));
        MeshResult result = new MeshResult();
        Snapshot s = snapshot(1, 1, 1, SHEET, 5, 1, 1, GLASS);
        s.fixedOrder = true;
        mesher.mesh(s, result);
        assertThat(result.fixedOrder()).isFalse();

        s = snapshot(1, 1, 1, SHEET, 1, 3, 1, SHEET);
        s.cameraY = 30.0F;
        mesher.mesh(s, result);
        assertThat(result.fixedOrder()).isFalse();
        // Sorted by centers from above: the lowest first.
        assertThat(quadPlanesY(result)).containsExactly(1.0F, 2.0F, 3.0F, 4.0F);
    }

    /** Each translucent quad's y, in the order the sorted indices draw them (the vanilla format). */
    private static float[] quadPlanesY(MeshResult result) {
        ByteBuffer indices = ByteBuffer.wrap(result.sortedIndexBytes()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer vertices = ByteBuffer.wrap(result.vertexBytes(MeshTables.LAYER_TRANSLUCENT))
                .order(ByteOrder.LITTLE_ENDIAN);
        int vertexSize =
                result.vertexByteCount(MeshTables.LAYER_TRANSLUCENT) / result.vertexCount(MeshTables.LAYER_TRANSLUCENT);
        float[] planes = new float[result.translucentQuadCount()];
        for (int q = 0; q < planes.length; q++) {
            int first = indices.getShort(q * 12) & 0xFFFF;
            planes[q] = vertices.getFloat(first * vertexSize + 4);
        }
        return planes;
    }

    @Test
    void anEmptySectionIsOpenEverywhere() {
        SectionMesher mesher = new SectionMesher(tables(true));
        MeshResult result = new MeshResult();
        mesher.mesh(snapshot(), result);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.visibility).isEqualTo((1L << 36) - 1);
    }

    @Test
    void aFullSectionIsClosed() {
        int[] blocks = new int[4096 * 4];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    blocks[i++] = x;
                    blocks[i++] = y;
                    blocks[i++] = z;
                    blocks[i++] = STONE;
                }
            }
        }
        MeshResult result = new MeshResult();
        new SectionMesher(tables(true)).mesh(snapshot(blocks), result);
        assertThat(result.visibility).isZero();
        // Only the faces toward the air around the section show.
        assertThat(result.vertexCount(MeshTables.LAYER_SOLID)).isEqualTo(6 * 256 * 4);
    }

    @Test
    void theFormatsSurviveTheTripToAWorker() {
        MeshTables tables = tables(true);
        Bytes.Out encodedTables = new Bytes.Out(1024);
        tables.encode(encodedTables);
        Snapshot s = snapshot(3, 4, 5, STONE, 3, 5, 5, GLASS);
        s.addTint(Snapshot.blockIndex(3, 4, 5), new int[] {0xFF123456}, 1, false, -1);
        s.addDynamic(Snapshot.index(0, 0, 0), true, 0b101, 0.2F);
        Bytes.Out encodedSnapshot = new Bytes.Out(1024);
        encodedSnapshot.putInt(MeshJobs.MESH);
        s.encode(encodedSnapshot);

        MeshJobs worker = new MeshJobs();
        Bytes.Out tablesMessage = new Bytes.Out(1024);
        tablesMessage.putInt(MeshJobs.TABLES);
        tablesMessage.putBytes(encodedTables.array(), 0, encodedTables.size());
        assertThat(worker.handle(tablesMessage.array(), 0, tablesMessage.size()))
                .isNull();
        Bytes.Out reply = worker.handle(encodedSnapshot.array(), 0, encodedSnapshot.size());
        assertThat(reply).isNotNull();
        Bytes.In in = new Bytes.In(reply.array(), 0, reply.size());
        assertThat(in.getInt()).isEqualTo(MeshJobs.OK);
        MeshResult remote = new MeshResult();
        remote.decode(in);

        MeshResult local = new MeshResult();
        new SectionMesher(MeshTables.decode(new Bytes.In(encodedTables.array(), 0, encodedTables.size())))
                .mesh(s, local);
        for (int layer = 0; layer < MeshTables.LAYER_COUNT; layer++) {
            assertThat(remote.vertexCount(layer)).isEqualTo(local.vertexCount(layer));
            assertThat(java.util.Arrays.copyOf(remote.vertexBytes(layer), remote.vertexByteCount(layer)))
                    .isEqualTo(java.util.Arrays.copyOf(local.vertexBytes(layer), local.vertexByteCount(layer)));
        }
        assertThat(remote.visibility).isEqualTo(local.visibility);
        assertThat(remote.translucentQuadCount()).isEqualTo(local.translucentQuadCount());
        for (int layer = 0; layer < MeshTables.LAYER_COUNT; layer++) {
            for (int run = 0; run < Facings.COUNT; run++) {
                assertThat(remote.facingRun(layer, run)).isEqualTo(local.facingRun(layer, run));
            }
        }
        assertThat(remote.animatedSprites()).isEqualTo(local.animatedSprites());
    }

    @Test
    void solidQuadsAreGroupedByFacingInTheOrderWritten() {
        SectionMesher mesher = new SectionMesher(tables(false));
        MeshResult result = new MeshResult();
        // Two cubes side by side along x: each writes its faces down, up, north, south, then its
        // free side, so the first cube's faces come before the second's within each facing.
        mesher.mesh(snapshot(5, 5, 5, STONE, 6, 5, 5, STONE), result);

        int[] runs = new int[Facings.COUNT];
        for (int run = 0; run < Facings.COUNT; run++) {
            runs[run] = result.facingRun(MeshTables.LAYER_SOLID, run);
        }
        assertThat(runs).containsExactly(0, 2, 2, 2, 2, 1, 1);
        ByteBuffer vertices =
                ByteBuffer.wrap(result.vertexBytes(MeshTables.LAYER_SOLID)).order(ByteOrder.LITTLE_ENDIAN);
        int quad = 4 * 28;
        // Both down faces first (at y 5, the first cube's at x 5 to 6, then the second's)...
        assertThat(vertices.getFloat(4)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(quad + 4)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(0)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(quad)).isEqualTo(6.0F);
        // ...and the west face (the first cube's, at x 5) just before the east one (at x 7).
        assertThat(vertices.getFloat(8 * quad)).isEqualTo(5.0F);
        assertThat(vertices.getFloat(9 * quad)).isEqualTo(7.0F);
        // The translucent layer is never regrouped: it's sorted.
        assertThat(result.facingRun(MeshTables.LAYER_TRANSLUCENT, 0)).isZero();
    }

    @Test
    void sectionsNoteTheAnimatedSpritesTheyDraw() {
        MeshTables.Builder b = new MeshTables.Builder(2, false);
        b.state(AIR, StateFlags.AIR | StateFlags.LIGHT_PERMEABLE, 0, 1.0F);
        int[][] faces = new int[7][];
        for (int d = 0; d < 6; d++) {
            int quad = b.quad(face(d), uvs(), d, MeshTables.LAYER_SOLID, -1, -1, 0);
            b.animatedQuad(quad, 70);
            faces[d] = new int[] {quad};
        }
        faces[6] = new int[0];
        b.state(STONE, StateFlags.MODEL | StateFlags.SOLID_RENDER | StateFlags.COLLISION_FULL, 0, 1.0F);
        b.model(STONE, b.singleNode(b.part(faces, false)));
        MeshTables tables = b.build();
        MeshResult result = new MeshResult();

        new SectionMesher(tables).mesh(snapshot(), result);
        assertThat(result.animatedSprites()[1]).isZero();
        new SectionMesher(tables).mesh(snapshot(1, 2, 3, STONE), result);
        assertThat(result.animatedSprites()[1]).isEqualTo(1L << 6);
    }

    @Test
    void facingsComeFromTheCornersAndTheirWinding() {
        int[] expected = {Dirs.DOWN, Dirs.UP, Dirs.NORTH, Dirs.SOUTH, Dirs.WEST, Dirs.EAST};
        for (int d = 0; d < 6; d++) {
            float[] p = face(d);
            assertThat(facing(p)).as("direction %d", d).isEqualTo(expected[d]);
            // Wound the other way, the same quad faces the other way (as fluids' back faces are).
            float[] reversed = {p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8], p[3], p[4], p[5]};
            assertThat(facing(reversed)).isEqualTo(Dirs.opposite(expected[d]));
        }
        // A crossed plant's diagonal quad, a degenerate one, and a face far outside the section.
        assertThat(facing(new float[] {0, 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0})).isEqualTo(Facings.UNASSIGNED);
        assertThat(facing(new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0})).isEqualTo(Facings.UNASSIGNED);
        float[] far = face(Dirs.UP);
        for (int v = 0; v < 4; v++) {
            far[v * 3 + 1] = 20.0F;
        }
        assertThat(facing(far)).isEqualTo(Facings.UNASSIGNED);
    }

    @Test
    void aCameraSeesFacesTowardItAndNoneFacingAwayFromAFarSection() {
        // Well above and east of the section: the up and east faces, not down or west.
        int bits = Facings.visible(30.0F, 40.0F, 8.0F);
        assertThat(bits & 1 << Dirs.UP).isNotZero();
        assertThat(bits & 1 << Dirs.DOWN).isZero();
        assertThat(bits & 1 << Dirs.EAST).isNotZero();
        assertThat(bits & 1 << Dirs.WEST).isZero();
        assertThat(bits & 1 << Dirs.NORTH).isNotZero();
        assertThat(bits & 1 << Dirs.SOUTH).isNotZero();
        assertThat(bits & 1 << Facings.UNASSIGNED).isNotZero();
        // Close to the section's top: a face just above it could still be seen from below.
        assertThat(Facings.visible(8.0F, 18.5F, 8.0F) & 1 << Dirs.DOWN).isNotZero();
    }

    @Test
    void visibleRunsNextToEachOtherAreOneRange() {
        // Runs: 3 unassigned, 2 down, 4 up, none north, 5 south, 1 west, 6 east.
        int[] runs = {9, 9, 3, 2, 4, 0, 5, 1, 6};
        int[] ranges = new int[2 * Facings.COUNT];
        // From above, north-east of the section: unassigned, up, south (none north... so up and
        // south touch), east; not down or west.
        int seen = 1 << Facings.UNASSIGNED | 1 << Dirs.UP | 1 << Dirs.SOUTH | 1 << Dirs.EAST;
        int count = Facings.visibleRanges(runs, 2, seen, ranges);
        assertThat(java.util.Arrays.copyOf(ranges, count * 2)).containsExactly(0, 3, 5, 9, 15, 6);
        // Everything seen: one range of all of them.
        assertThat(Facings.visibleRanges(runs, 2, 0x7F, ranges)).isEqualTo(1);
        assertThat(ranges[0]).isZero();
        assertThat(ranges[1]).isEqualTo(21);
    }

    @Test
    void bakedQuadsGoIntoTheCutoutLayerAsTheyAre() {
        Snapshot s = snapshot(1, 1, 1, STONE);
        float[] positions = face(Dirs.UP);
        for (int v = 0; v < 4; v++) {
            positions[v * 3] += 4.0F;
        }
        s.addBakedQuad(positions, uvs(), new int[] {0xFF666666, 0xFF666666, 0xFF999999, 0xFF999999}, 0xF000A0);
        Bytes.Out encoded = new Bytes.Out(1024);
        s.encode(encoded);
        Snapshot decoded = new Snapshot();
        decoded.decode(new Bytes.In(encoded.array(), 0, encoded.size()));
        assertThat(decoded.bakedCount()).isEqualTo(1);

        MeshResult result = new MeshResult();
        new SectionMesher(tables(false)).mesh(decoded, result);
        assertThat(result.vertexCount(MeshTables.LAYER_CUTOUT)).isEqualTo(4);
        ByteBuffer vertices =
                ByteBuffer.wrap(result.vertexBytes(MeshTables.LAYER_CUTOUT)).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(vertices.getFloat(0)).isEqualTo(4.0F);
        assertThat(vertices.getFloat(4)).isEqualTo(1.0F);
        // Colors as given (ARGB in, ABGR bytes out), the light as given.
        assertThat(vertices.getInt(12)).isEqualTo(0xFF666666);
        assertThat(vertices.getInt(2 * 28 + 12)).isEqualTo(0xFF999999);
        assertThat(vertices.getInt(24)).isEqualTo(0xF000A0);
        // It's an up face: its run is the up run.
        assertThat(result.facingRun(MeshTables.LAYER_CUTOUT, Facings.runOf(Dirs.UP)))
                .isEqualTo(1);
    }

    private static int facing(float[] p) {
        return Facings.of(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11]);
    }

    @Test
    void compactVerticesQuantizeWithinHalfAStep() {
        SectionMesher mesher = new SectionMesher(tables(false));
        Snapshot s = snapshot(15, 0, 7, STONE);
        s.vertexFormat = Snapshot.FORMAT_COMPACT;
        MeshResult result = new MeshResult();
        mesher.mesh(s, result);
        ByteBuffer vertices =
                ByteBuffer.wrap(result.vertexBytes(MeshTables.LAYER_SOLID)).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(result.vertexByteCount(MeshTables.LAYER_SOLID)).isEqualTo(24 * 16);
        // The down face's first corner: (15, 0, 8).
        assertThat((vertices.getShort(0) & 0xFFFF) / 2048.0F - 8.0F).isEqualTo(15.0F);
        assertThat((vertices.getShort(2) & 0xFFFF) / 2048.0F - 8.0F).isEqualTo(0.0F);
        assertThat((vertices.getShort(4) & 0xFFFF) / 2048.0F - 8.0F).isEqualTo(8.0F);
        assertThat(vertices.get(7) & 0xFF).isEqualTo(240);
        assertThat((vertices.getShort(14) & 0xFFFF) / 32768.0F).isEqualTo(0.0F);
    }
}
