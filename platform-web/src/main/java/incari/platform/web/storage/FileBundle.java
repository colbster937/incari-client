package incari.platform.web.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Files and store changes as bytes, for the page and a copy of the game in a worker (ADR 0039):
 * a world's files go to the worker when its server starts and come back when it stops, and the
 * worker's changes to them reach the page's store this way.
 */
final class FileBundle {

    private FileBundle() {}

    /**
     * A file or folder as the store would load it. A stored entry is an in-place file (ADR 0041):
     * its size, its bytes left in the store.
     */
    @SuppressWarnings("ArrayRecordComponent") // Carries bytes; never compared or hashed.
    record Entry(
            String path, boolean directory, long lastModified, byte @Nullable [] contents, boolean stored, int size) {

        static Entry folder(String path, long lastModified) {
            return new Entry(path, true, lastModified, null, false, 0);
        }

        static Entry file(String path, long lastModified, byte[] contents) {
            return new Entry(path, false, lastModified, contents, false, contents.length);
        }

        static Entry stored(String path, long lastModified, int size) {
            return new Entry(path, false, lastModified, null, true, size);
        }
    }

    /**
     * A folder handed to another copy of the program, or back: its entries, and whether its
     * in-place files are left in the store for the other copy to open where they are (ADR 0041).
     */
    record Bundle(boolean inPlace, List<Entry> entries) {}

    /** How a stored entry's contents length is written: it has none, and a size. */
    private static final int STORED = -2;

    static byte[] encodeEntries(List<Entry> entries) {
        return encode(new Bundle(false, entries));
    }

    static List<Entry> decodeEntries(byte[] bundle) {
        return decode(bundle).entries();
    }

    static byte[] encode(Bundle bundle) {
        List<Entry> entries = bundle.entries();
        int size = 4;
        for (Entry entry : entries) {
            byte[] contents = entry.contents();
            size += entry.path().length() * 3 + 2 + 1 + 8 + 4 + 4 + (contents == null ? 0 : contents.length);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size + 1);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeBoolean(bundle.inPlace());
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                out.writeUTF(entry.path());
                out.writeBoolean(entry.directory());
                out.writeLong(entry.lastModified());
                byte[] contents = entry.contents();
                if (entry.stored()) {
                    out.writeInt(STORED);
                    out.writeInt(entry.size());
                } else {
                    out.writeInt(contents == null ? -1 : contents.length);
                    if (contents != null) {
                        out.write(contents);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Bundle decode(byte[] bundle) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bundle))) {
            boolean inPlace = in.readBoolean();
            int count = in.readInt();
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String path = in.readUTF();
                boolean directory = in.readBoolean();
                long lastModified = in.readLong();
                int length = in.readInt();
                if (length == STORED) {
                    entries.add(Entry.stored(path, lastModified, in.readInt()));
                } else if (length < 0) {
                    entries.add(new Entry(path, directory, lastModified, null, false, 0));
                } else {
                    byte[] contents = new byte[length];
                    in.readFully(contents);
                    entries.add(new Entry(path, directory, lastModified, contents, false, length));
                }
            }
            return new Bundle(inPlace, entries);
        } catch (IOException e) {
            throw new UncheckedIOException("A damaged bundle of files", e);
        }
    }

    static byte[] encodeOps(List<StoreOp> ops) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(ops.size());
            for (StoreOp op : ops) {
                out.writeUTF(op.kind().name());
                out.writeUTF(op.path());
                out.writeInt(op.size());
                out.writeInt(op.at());
                byte[] data = op.data();
                out.writeInt(data == null ? -1 : data.length);
                if (data != null) {
                    out.write(data);
                }
                out.writeLong(op.lastModified());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static List<StoreOp> decodeOps(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int count = in.readInt();
            List<StoreOp> ops = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                StoreOp.Kind kind = StoreOp.Kind.valueOf(in.readUTF());
                String path = in.readUTF();
                int size = in.readInt();
                int at = in.readInt();
                byte[] data = readBytes(in);
                long lastModified = in.readLong();
                ops.add(new StoreOp(kind, path, size, at, data, lastModified));
            }
            return ops;
        } catch (IOException e) {
            throw new UncheckedIOException("Damaged store changes", e);
        }
    }

    private static byte @Nullable [] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        byte[] contents = new byte[length];
        in.readFully(contents);
        return contents;
    }
}
