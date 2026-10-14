package incari.platform.web.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.teavm.runtime.fs.VirtualFileAccessor;

/**
 * A world's region files left in the store (ADR 0041): the page reads one only when it opens it,
 * the server's worker opens them where they are, and neither sends the store what it already has.
 */
class InPlaceTest {

    private static final String WORLD = "/incari/game/saves/World";
    private static final String REGION = WORLD + "/region/r.0.0.mca";

    /** The store: files by path, opened handles counted. */
    private static final class Store implements InPlaceStore {
        final Map<String, byte[]> files = new HashMap<>();
        final boolean inPlace;
        int reads;
        int open;
        final List<String> opened = new ArrayList<>();

        Store(boolean inPlace) {
            this.inPlace = inPlace;
        }

        @Override
        public boolean opensInPlace() {
            return inPlace;
        }

        @Override
        public byte[] read(String path) {
            reads++;
            byte[] bytes = files.get(path);
            return bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public Handle open(String path) {
            opened.add(path);
            open++;
            files.putIfAbsent(path, new byte[0]);
            return new Handle() {
                boolean closed;

                @Override
                public int read(byte[] buffer, int offset, int length, int at) {
                    byte[] bytes = files.get(path);
                    int count = Math.max(0, Math.min(length, bytes.length - at));
                    System.arraycopy(bytes, at, buffer, offset, count);
                    return count;
                }

                @Override
                public void write(byte[] buffer, int offset, int length, int at) {
                    byte[] bytes = files.get(path);
                    if (at + length > bytes.length) {
                        bytes = Arrays.copyOf(bytes, at + length);
                    }
                    System.arraycopy(buffer, offset, bytes, at, length);
                    files.put(path, bytes);
                }

                @Override
                public int size() {
                    return files.get(path).length;
                }

                @Override
                public void truncate(int size) {
                    files.put(path, Arrays.copyOf(files.get(path), size));
                }

                @Override
                public void flush() {}

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        open--;
                    }
                }
            };
        }
    }

    private static TreeFileSystem tree(Store store) {
        TreeFileSystem files = new TreeFileSystem(new Journal("/incari", List.of()), () -> 3_000);
        files.setInPlaceStore(store);
        return files;
    }

    @Test
    void thePageReadsARegionFileOnlyWhenItOpensIt() throws IOException {
        Store store = new Store(false);
        store.files.put(REGION, bytes("chunks"));
        TreeFileSystem page = tree(store);
        page.loadStored(REGION, 700, 6);
        page.collectChanges();

        assertThat(page.getFile(REGION).length()).isEqualTo(6);
        assertThat(store.reads).isZero();
        assertThat(read(page, REGION)).isEqualTo("chunks");
        assertThat(read(page, REGION)).isEqualTo("chunks");
        assertThat(store.reads).isEqualTo(1);
        // Read, not changed: nothing to tell the store.
        assertThat(page.collectChanges()).isEmpty();
    }

    @Test
    void theWorkerOpensRegionFilesWhereTheyAreAndTheJournalHasNothingToSend() throws IOException {
        Store store = new Store(true);
        store.files.put(REGION, bytes("chunks"));
        TreeFileSystem worker = tree(store);
        worker.load(WORLD, true, 1, null);
        worker.load(WORLD + "/region", true, 1, null);
        worker.loadStored(REGION, 700, 6);
        worker.collectChanges();

        VirtualFileAccessor region = worker.getFile(REGION).createAccessor(true, true, false);
        region.seek(6);
        region.write(bytes("+more"), 0, 5);
        region.close();
        assertThat(store.files.get(REGION)).isEqualTo(bytes("chunks+more"));
        assertThat(worker.getFile(REGION).length()).isEqualTo(11);
        assertThat(read(worker, REGION)).isEqualTo("chunks+more");
        assertThat(store.opened).containsExactly(REGION);

        // A new region file is made in the store when the server first opens it.
        worker.getFile(WORLD + "/region").createFile("r.1.0.mca");
        VirtualFileAccessor next = worker.getFile(WORLD + "/region/r.1.0.mca").createAccessor(true, true, false);
        next.write(bytes("new"), 0, 3);
        assertThat(store.files.get(WORLD + "/region/r.1.0.mca")).isEqualTo(bytes("new"));
        assertThat(worker.collectChanges()).isEmpty();

        // Handed back: sizes only, every handle let go.
        worker.closeStored(WORLD);
        assertThat(store.open).isZero();
        assertThat(worker.export(WORLD, true))
                .filteredOn(FileBundle.Entry::stored)
                .extracting(FileBundle.Entry::path, FileBundle.Entry::size)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(REGION, 11),
                        org.assertj.core.groups.Tuple.tuple(WORLD + "/region/r.1.0.mca", 3));
    }

    @Test
    void handedOverInPlaceTheBundleCarriesSizesAndComesBackTheSame() throws IOException {
        Store store = new Store(false);
        TreeFileSystem page = tree(store);
        page.load(WORLD + "/level.dat", false, 500, bytes("level"));
        page.loadStored(REGION, 700, 6);
        // Written on the page (an imported world): in the store once flushed, sent as a size too.
        page.load(WORLD + "/region/r.5.5.mca", false, 800, bytes("imported"));
        page.collectChanges();

        byte[] encoded = FileBundle.encode(new FileBundle.Bundle(true, page.export(WORLD, true)));
        FileBundle.Bundle bundle = FileBundle.decode(encoded);

        assertThat(bundle.inPlace()).isTrue();
        assertThat(bundle.entries())
                .extracting(FileBundle.Entry::path, FileBundle.Entry::stored, FileBundle.Entry::size)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(WORLD + "/level.dat", false, 5),
                        org.assertj.core.groups.Tuple.tuple(REGION, true, 6),
                        org.assertj.core.groups.Tuple.tuple(WORLD + "/region/r.5.5.mca", true, 8));
        assertThat(store.reads).isZero();
    }

    @Test
    void handedOverWithTheirBytesWhenNotInPlace() throws IOException {
        Store store = new Store(false);
        store.files.put(REGION, bytes("chunks"));
        TreeFileSystem page = tree(store);
        page.loadStored(REGION, 700, 6);

        List<FileBundle.Entry> entries = page.export(WORLD, false);

        assertThat(entries)
                .filteredOn(entry -> entry.path().equals(REGION))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.stored()).isFalse();
                    assertThat(entry.contents()).isEqualTo(bytes("chunks"));
                });
    }

    @Test
    void thePageMovesARegionFileByReadingItAndTheWorkerWont() throws IOException {
        Store pageStore = new Store(false);
        pageStore.files.put(REGION, bytes("chunks"));
        TreeFileSystem page = tree(pageStore);
        page.load("/incari/game/saves", true, 1, null);
        page.loadStored(REGION, 700, 6);
        page.collectChanges();
        assertThat(page.getFile("/incari/game/saves").adopt(page.getFile(WORLD), "Renamed"))
                .isTrue();
        assertThat(read(page, "/incari/game/saves/Renamed/region/r.0.0.mca")).isEqualTo("chunks");
        assertThat(page.collectChanges())
                .extracting(StoreOp::kind, StoreOp::path)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        StoreOp.Kind.WRITE, "/incari/game/saves/Renamed/region/r.0.0.mca"));

        Store workerStore = new Store(true);
        TreeFileSystem worker = tree(workerStore);
        worker.load("/incari/game/saves", true, 1, null);
        worker.loadStored(REGION, 700, 6);
        assertThat(worker.getFile("/incari/game/saves").adopt(worker.getFile(WORLD), "Renamed"))
                .isFalse();
    }

    @Test
    void deletingARegionFileLetsItsHandleGoAndDeletesItFromTheStore() throws IOException {
        Store store = new Store(true);
        store.files.put(REGION, bytes("chunks"));
        TreeFileSystem worker = tree(store);
        worker.loadStored(REGION, 700, 6);
        worker.collectChanges();
        worker.getFile(REGION).createAccessor(true, true, false).close();
        assertThat(store.open).isEqualTo(1);

        assertThat(worker.getFile(REGION).delete()).isTrue();

        assertThat(store.open).isZero();
        assertThat(worker.collectChanges())
                .extracting(StoreOp::kind, StoreOp::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(StoreOp.Kind.DELETE, REGION));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(TreeFileSystem files, String path) throws IOException {
        VirtualFileAccessor file = files.getFile(path).createAccessor(true, false, false);
        byte[] buffer = new byte[file.size()];
        int count = file.read(buffer, 0, buffer.length);
        file.close();
        return new String(buffer, 0, count, StandardCharsets.UTF_8);
    }
}
