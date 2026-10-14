package incari.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DroppedFilesTest {

    @TempDir
    Path root;

    @Test
    void filesAndFoldersAreCopiedAndTheDroppedOnesReported() throws IOException {
        DroppedFiles drops = new DroppedFiles(root.toFile());
        List<String> paths = drops.store(
                1,
                new Drop()
                        .file("pack.zip", "zip")
                        .folder("My Pack")
                        .file("My Pack/pack.mcmeta", "{}")
                        .folder("My Pack/assets")
                        .file("My Pack/assets/a.png", "png"));
        Path drop = root.resolve("1");
        assertThat(paths)
                .containsExactly(
                        drop.resolve("pack.zip").toString(),
                        drop.resolve("My Pack").toString());
        assertThat(drop.resolve("pack.zip")).hasContent("zip");
        assertThat(drop.resolve("My Pack/pack.mcmeta")).hasContent("{}");
        assertThat(drop.resolve("My Pack/assets/a.png")).hasContent("png");
    }

    @Test
    void filesInAFolderArriveBeforeTheFolderItself() throws IOException {
        // The page reads folders asynchronously, so their entries can come in any order.
        List<String> paths = new DroppedFiles(root.toFile())
                .store(
                        1,
                        new Drop()
                                .file("Pack/data/x.json", "1")
                                .folder("Pack/data")
                                .folder("Pack"));
        assertThat(paths).containsExactly(root.resolve("1/Pack").toString());
        assertThat(root.resolve("1/Pack/data/x.json")).hasContent("1");
    }

    @Test
    void onlyTheLatestDropIsKept() throws IOException {
        DroppedFiles drops = new DroppedFiles(root.toFile());
        drops.store(1, new Drop().folder("A").file("A/a.txt", "a"));
        drops.store(2, new Drop().file("b.txt", "b"));
        assertThat(root.resolve("1")).doesNotExist();
        assertThat(root.resolve("2/b.txt")).hasContent("b");
    }

    @Test
    void namesCantLeaveTheDropsFolder() throws IOException {
        List<String> paths = new DroppedFiles(root.toFile())
                .store(1, new Drop().file("../../escape.txt", "x").file("/abs/./x.txt", "y"));
        assertThat(paths)
                .containsExactly(
                        root.resolve("1/escape.txt").toString(),
                        root.resolve("1/abs").toString());
        assertThat(root.resolve("escape.txt")).doesNotExist();
        assertThat(root.resolve("1/abs/x.txt")).hasContent("y");
    }

    @Test
    void safePathsDropDotsAndReplaceWhatFileSystemsReject() {
        assertThat(DroppedFiles.safePath("a/b.zip")).isEqualTo("a/b.zip");
        assertThat(DroppedFiles.safePath("a//./../b")).isEqualTo("a/b");
        assertThat(DroppedFiles.safePath("what?:*<>|\"\\x\u0001.zip")).isEqualTo("what________x_.zip");
        assertThat(DroppedFiles.safePath("Ünïcødé pack.zip")).isEqualTo("Ünïcødé pack.zip");
        assertThat(DroppedFiles.safePath("")).isNull();
        assertThat(DroppedFiles.safePath("../..")).isNull();
    }

    @Test
    void emptyEntriesAreSkipped() throws IOException {
        List<String> paths = new DroppedFiles(root.toFile())
                .store(1, new Drop().file("..", "x").file("ok", ""));
        assertThat(paths).containsExactly(root.resolve("1/ok").toString());
        assertThat(new File(root.toFile(), "1/ok")).exists().isEmpty();
    }

    /** A drop as the page would hand it over. */
    private static final class Drop implements DroppedFiles.Source {
        private final List<String> paths = new ArrayList<>();
        private final List<byte @Nullable []> contents = new ArrayList<>();

        Drop file(String path, String content) {
            paths.add(path);
            contents.add(content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Drop folder(String path) {
            paths.add(path);
            contents.add(null);
            return this;
        }

        @Override
        public int count() {
            return paths.size();
        }

        @Override
        public String path(int index) {
            return paths.get(index);
        }

        @Override
        public boolean isFolder(int index) {
            return contents.get(index) == null;
        }

        @Override
        public byte[] bytes(int index) {
            byte[] content = contents.get(index);
            if (content == null) {
                throw new IllegalStateException("A folder has no contents");
            }
            return content;
        }
    }
}
