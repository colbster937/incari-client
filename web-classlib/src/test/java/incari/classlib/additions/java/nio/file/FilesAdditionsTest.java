package incari.classlib.additions.java.nio.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesAdditionsTest {

    @Test
    void findMatchesTheJdk(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(root.resolve("a/one.json"), "1");
        Files.writeString(root.resolve("a/b/two.json"), "2");
        Files.writeString(root.resolve("a/b/skip.txt"), "3");
        for (int depth : new int[] {0, 1, 2, Integer.MAX_VALUE}) {
            try (Stream<Path> ours =
                            FilesAdditions.find(root, depth, (path, attributes) -> attributes.isRegularFile());
                    Stream<Path> jdks = Files.find(root, depth, (path, attributes) -> attributes.isRegularFile())) {
                assertThat(sorted(ours)).as("files to depth %d", depth).isEqualTo(sorted(jdks));
            }
            try (Stream<Path> ours = FilesAdditions.find(root, depth, (path, attributes) -> true);
                    Stream<Path> jdks = Files.find(root, depth, (path, attributes) -> true)) {
                assertThat(sorted(ours)).as("everything to depth %d", depth).isEqualTo(sorted(jdks));
            }
        }
    }

    private static List<Path> sorted(Stream<Path> paths) {
        return paths.sorted().collect(Collectors.toList());
    }
}
