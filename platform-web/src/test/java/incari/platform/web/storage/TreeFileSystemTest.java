package incari.platform.web.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;

/** The tree as TeaVM's file classes use it, through {@code VirtualFile} and its accessors. */
class TreeFileSystemTest {

    private long time = 1_000;
    private TreeFileSystem files;

    @BeforeEach
    void setUp() throws IOException {
        files = new TreeFileSystem(new Journal("/incari", List.of("/incari/dropped")), () -> time);
        files.setWorkingDirectory("/incari/game");
    }

    @Test
    void pathsAreNormalizedAndRelativeOnesStartAtTheWorkingDirectory() {
        assertThat(files.normalize("/a//b/./c/../d/")).isEqualTo("/a/b/d");
        assertThat(files.normalize("/..")).isEqualTo("/");
        assertThat(files.normalize("options.txt")).isEqualTo("/incari/game/options.txt");
        assertThat(files.canonicalize("../x")).isEqualTo("/incari/x");
        assertThat(files.getUserDir()).isEqualTo("/incari/game");
        assertThat(files.getFile("/").isDirectory()).isTrue();
    }

    @Test
    void filesAreCreatedWrittenAndRead() throws IOException {
        VirtualFile game = files.getFile("/incari/game");
        assertThat(game.createFile("options.txt")).isTrue();
        assertThat(game.createFile("options.txt")).isFalse();
        write("/incari/game/options.txt", "fov:90");
        assertThat(read("/incari/game/options.txt")).isEqualTo("fov:90");
        assertThat(files.getFile("options.txt").length()).isEqualTo(6);
        assertThat(game.listFiles()).containsExactly("options.txt");
        assertThat(files.getFile("/incari/game/options.txt").isFile()).isTrue();
        assertThat(files.getFile("/incari/game/options.txt/x").exists()).isFalse();
    }

    @Test
    void writingWithoutReadingReplacesTheContentsButReadWriteKeepsThem() throws IOException {
        files.getFile("/incari/game").createFile("f");
        write("/incari/game/f", "hello world");
        // RandomAccessFile(file, "rw"): TeaVM's own file system empties the file here, the JDK doesn't.
        VirtualFileAccessor both = files.getFile("/incari/game/f").createAccessor(true, true, false);
        assertThat(both.size()).isEqualTo(11);
        both.seek(6);
        both.write(bytes("there"), 0, 5);
        both.close();
        assertThat(read("/incari/game/f")).isEqualTo("hello there");
        // FileOutputStream(file): a new file's worth.
        write("/incari/game/f", "hi");
        assertThat(read("/incari/game/f")).isEqualTo("hi");
        // FileOutputStream(file, true): appended.
        VirtualFileAccessor append = files.getFile("/incari/game/f").createAccessor(false, true, true);
        append.write(bytes("!"), 0, 1);
        assertThat(read("/incari/game/f")).isEqualTo("hi!");
    }

    @Test
    void aSeekPastTheEndLeavesZerosAndAShrinkForgetsWhatWasCut() throws IOException {
        files.getFile("/incari/game").createFile("f");
        VirtualFileAccessor file = files.getFile("/incari/game/f").createAccessor(true, true, false);
        file.write(bytes("abcdef"), 0, 6);
        file.resize(2);
        file.seek(4);
        file.write(bytes("z"), 0, 1);
        assertThat(file.size()).isEqualTo(5);
        byte[] all = new byte[5];
        file.seek(0);
        assertThat(file.read(all, 0, 5)).isEqualTo(5);
        assertThat(all).containsExactly('a', 'b', 0, 0, 'z');
        assertThat(file.read(all, 0, 5)).isZero();
    }

    @Test
    void foldersAreMadeListedAndDeletedWhenEmpty() {
        VirtualFile game = files.getFile("/incari/game");
        assertThat(game.createDirectory("saves")).isTrue();
        assertThat(game.createDirectory("saves")).isFalse();
        assertThat(files.getFile("/incari/game/saves").createDirectory("World")).isTrue();
        assertThat(files.getFile("/incari/game/saves").delete()).isFalse();
        assertThat(files.getFile("/incari/game/saves/World").delete()).isTrue();
        assertThat(files.getFile("/incari/game/saves").delete()).isTrue();
        assertThat(files.getFile("/incari/game/saves").exists()).isFalse();
        assertThat(files.getFile("/").delete()).isFalse();
    }

    @Test
    void createFileInAMissingFolderFails() {
        assertThatThrownBy(() -> files.getFile("/incari/nowhere").createFile("x"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void renamesMoveFilesAndReplaceFilesButNotFolders() throws IOException {
        VirtualFile game = files.getFile("/incari/game");
        game.createFile("servers123.dat");
        write("/incari/game/servers123.dat", "new");
        game.createFile("servers.dat");
        write("/incari/game/servers.dat", "old");
        assertThat(game.adopt(files.getFile("/incari/game/servers123.dat"), "servers.dat"))
                .isTrue();
        assertThat(read("/incari/game/servers.dat")).isEqualTo("new");
        assertThat(files.getFile("/incari/game/servers123.dat").exists()).isFalse();

        game.createDirectory("dir");
        files.getFile("/incari/game/dir").createFile("inside");
        assertThat(game.adopt(files.getFile("/incari/game/servers.dat"), "dir")).isFalse();
        // A folder can't go inside itself.
        assertThat(files.getFile("/incari/game/dir").adopt(files.getFile("/incari/game/dir"), "again"))
                .isFalse();
        assertThat(files.getFile("/incari").adopt(files.getFile("/incari/game/dir"), "moved"))
                .isTrue();
        assertThat(files.getFile("/incari/moved/inside").isFile()).isTrue();
    }

    @Test
    void anOpenFileKeepsWorkingAfterItsDeleted() throws IOException {
        files.getFile("/incari/game").createFile("f");
        VirtualFileAccessor open = files.getFile("/incari/game/f").createAccessor(true, true, false);
        assertThat(files.getFile("/incari/game/f").delete()).isTrue();
        open.write(bytes("still"), 0, 5);
        assertThat(open.size()).isEqualTo(5);
        assertThat(files.getFile("/incari/game/f").exists()).isFalse();
    }

    @Test
    void timesFollowWrites() throws IOException {
        files.getFile("/incari/game").createFile("f");
        time = 5_000;
        write("/incari/game/f", "x");
        assertThat(files.getFile("/incari/game/f").lastModified()).isEqualTo(5_000);
        assertThat(files.getFile("/incari/game/f").setLastModified(42)).isTrue();
        assertThat(files.getFile("/incari/game/f").lastModified()).isEqualTo(42);
    }

    @Test
    void readOnlyFilesRefuseWriters() throws IOException {
        files.getFile("/incari/game").createFile("f");
        assertThat(files.getFile("/incari/game/f").setReadOnly(true)).isTrue();
        assertThat(files.getFile("/incari/game/f").canWrite()).isFalse();
        assertThat(files.getFile("/incari/game/f").createAccessor(false, true, false))
                .isNull();
        assertThat(files.getFile("/incari/game/f").createAccessor(true, false, false))
                .isNotNull();
    }

    @Test
    void storedFilesLoadWithoutCountingAsChanges() throws IOException {
        files.load("/incari/game/servers.dat", false, 77, bytes("stored"));
        files.load("/incari/game/saves", true, 78, null);
        assertThat(read("/incari/game/servers.dat")).isEqualTo("stored");
        assertThat(files.getFile("/incari/game/servers.dat").lastModified()).isEqualTo(77);
        assertThat(files.getFile("/incari/game/saves").isDirectory()).isTrue();
        assertThat(files.hasChanges()).isFalse();
    }

    private void write(String path, String text) throws IOException {
        VirtualFileAccessor accessor = files.getFile(path).createAccessor(false, true, false);
        byte[] bytes = bytes(text);
        accessor.write(bytes, 0, bytes.length);
        accessor.close();
    }

    private String read(String path) throws IOException {
        VirtualFileAccessor accessor = files.getFile(path).createAccessor(true, false, false);
        byte[] bytes = new byte[accessor.size()];
        int read = accessor.read(bytes, 0, bytes.length);
        accessor.close();
        return new String(bytes, 0, read, StandardCharsets.UTF_8);
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
