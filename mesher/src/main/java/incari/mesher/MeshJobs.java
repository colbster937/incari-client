package incari.mesher;

import org.jspecify.annotations.Nullable;

/**
 * A mesher worker's side of the conversation: it takes messages (new tables, a section to mesh, a
 * translucent layer to sort again) and answers each with bytes. The page's workers run it inside
 * their own WebAssembly module ({@code incari.app.web.mesher.MesherWorker}); the game's own side of
 * the messages is {@code incari.client.render.mesher.WorkerMeshing}.
 *
 * <p>Messages start with a kind. {@link #TABLES}: the encoded {@link MeshTables}, no answer.
 * {@link #MESH}: an encoded {@link Snapshot}, answered with a status and, if it's {@link #OK} or
 * {@link #UNSUPPORTED}, the encoded {@link MeshResult}. {@link #SORT}: the count of quads, the
 * camera (three floats), whether indices are 32-bit, and the quads' centers; answered with a status
 * and the index buffer. A failure answers {@link #FAILED} and the error's description.
 */
public final class MeshJobs {

    public static final int TABLES = 1;
    public static final int MESH = 2;
    public static final int SORT = 3;

    public static final int OK = 0;
    /** Meshed, but the section holds a state the tables couldn't describe. */
    public static final int UNSUPPORTED = 1;

    public static final int FAILED = 2;
    /** A mesh or sort before any tables arrived. */
    public static final int NO_TABLES = 3;

    private @Nullable SectionMesher mesher;
    private final QuadSorter sorter = new QuadSorter();
    private final Snapshot snapshot = new Snapshot();
    private final MeshResult result = new MeshResult();
    private final Bytes.Out reply = new Bytes.Out(256 * 1024);
    private final Bytes.Out indices = new Bytes.Out(16 * 1024);
    private float[] centroids = new float[768];

    /**
     * Handles one message.
     *
     * @return the answer, valid until the next call, or null for a message that has none
     */
    public Bytes.@Nullable Out handle(byte[] message, int offset, int length) {
        reply.clear();
        Bytes.In in = new Bytes.In(message, offset, length);
        int kind = in.getInt();
        try {
            if (kind == TABLES) {
                mesher = new SectionMesher(MeshTables.decode(in));
                return null;
            }
            SectionMesher current = mesher;
            if (current == null) {
                reply.putInt(NO_TABLES);
            } else if (kind == MESH) {
                mesh(current, in);
            } else if (kind == SORT) {
                sort(in);
            } else {
                throw new IllegalArgumentException("Unknown mesher message " + kind);
            }
        } catch (RuntimeException e) {
            reply.clear();
            reply.putInt(FAILED);
            String description = String.valueOf(e);
            byte[] text = new byte[description.length()];
            for (int i = 0; i < text.length; i++) {
                char c = description.charAt(i);
                text[i] = (byte) (c < 128 ? c : '?');
            }
            reply.putByteArray(text, text.length);
        }
        return reply;
    }

    private void mesh(SectionMesher current, Bytes.In in) {
        snapshot.decode(in);
        boolean supported = current.mesh(snapshot, result);
        reply.putInt(supported ? OK : UNSUPPORTED);
        result.encode(reply);
    }

    private void sort(Bytes.In in) {
        int count = in.getInt();
        float x = in.getFloat();
        float y = in.getFloat();
        float z = in.getFloat();
        boolean intIndices = in.getByte() != 0;
        if (centroids.length < count * 3) {
            centroids = new float[count * 3];
        }
        for (int i = 0; i < count * 3; i++) {
            centroids[i] = in.getFloat();
        }
        sorter.sortInto(centroids, count, x, y, z, intIndices, indices);
        reply.putInt(OK);
        reply.putByteArray(indices.array(), indices.size());
    }

    /** Encodes a sort request for {@link #handle}. */
    public static void encodeSort(
            Bytes.Out out, float[] centroids, int count, float x, float y, float z, boolean intIndices) {
        out.putInt(SORT);
        out.putInt(count);
        out.putFloat(x);
        out.putFloat(y);
        out.putFloat(z);
        out.putByte(intIndices ? 1 : 0);
        for (int i = 0; i < count * 3; i++) {
            out.putFloat(centroids[i]);
        }
    }
}
