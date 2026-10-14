package incari.platform.web.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.teavm.runtime.fs.VirtualFileAccessor;

/** A world's folder handed to a worker's copy of the game and back (ADR 0039). */
class HandOverTest {

    private final TreeFileSystem page = new TreeFileSystem(new Journal("/incari", List.of()), () -> 1_000);
    private final TreeFileSystem worker = new TreeFileSystem(new Journal("/incari", List.of()), () -> 2_000);

    @Test
    void aFolderGoesOverWithoutTellingTheStoreAndComesBack() throws IOException {
        page.load("/incari/game/saves/World/level.dat", false, 500, bytes("level"));
        page.load("/incari/game/saves/World/region", true, 600, null);
        page.load("/incari/game/saves/World/region/r.0.0.mca", false, 700, bytes("chunks"));
        page.load("/incari/game/options.txt", false, 800, bytes("fov:90"));
        page.collectChanges();

        byte[] bundle = FileBundle.encodeEntries(page.export("/incari/game/saves/World"));
        page.forget("/incari/game/saves/World");

        assertThat(page.getFile("/incari/game/saves/World").exists()).isFalse();
        assertThat(page.getFile("/incari/game/options.txt").exists()).isTrue();
        assertThat(page.collectChanges()).isEmpty();

        for (FileBundle.Entry entry : FileBundle.decodeEntries(bundle)) {
            worker.load(entry.path(), entry.directory(), entry.lastModified(), entry.contents());
        }
        assertThat(read(worker, "/incari/game/saves/World/region/r.0.0.mca")).isEqualTo("chunks");
        assertThat(worker.getFile("/incari/game/saves/World/level.dat").lastModified())
                .isEqualTo(500);
        assertThat(worker.collectChanges()).isEmpty();
    }

    @Test
    void changesGoToThePageAsTheyWereMade() throws IOException {
        worker.load("/incari/game/saves/World/level.dat", false, 500, bytes("level"));
        worker.collectChanges();
        VirtualFileAccessor file =
                worker.getFile("/incari/game/saves/World/level.dat").createAccessor(true, true, false);
        file.seek(5);
        file.write(bytes("!"), 0, 1);
        file.close();

        List<StoreOp> sent = FileBundle.decodeOps(FileBundle.encodeOps(worker.collectChanges()));

        assertThat(sent).singleElement().satisfies(op -> {
            assertThat(op.kind()).isEqualTo(StoreOp.Kind.WRITE);
            assertThat(op.path()).isEqualTo("/incari/game/saves/World/level.dat");
            assertThat(op.size()).isEqualTo(6);
            assertThat(op.at()).isEqualTo(5);
            assertThat(op.data()).containsExactly('!');
        });
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(TreeFileSystem files, String path) throws IOException {
        VirtualFileAccessor file = files.getFile(path).createAccessor(true, false, false);
        byte[] buffer = new byte[file.size()];
        file.read(buffer, 0, buffer.length);
        file.close();
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
