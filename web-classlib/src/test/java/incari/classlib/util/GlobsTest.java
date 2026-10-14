package incari.classlib.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Matches paths the way the JDK's default file system does (on Unix-style paths). */
class GlobsTest {

    private static final String[] PATHS = {
        "a.txt", "b.json", "dir/a.txt", "dir/sub/c.json", ".hidden", "x", "abc", "a{b", "a,b", "[x]", "a-b"
    };

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*.txt",
                "**.json",
                "*/*.txt",
                "**/*.json",
                "?",
                "a?c",
                "[ab].*",
                "[!a]*",
                "[a-c]bc",
                "{a,b}.*",
                "*.{txt,json}",
                "a\\{b",
                "a,b",
                "\\[x\\]",
                "a-b",
                "dir/**"
            })
    void agreesWithTheJdk(String glob) {
        PathMatcher jdk = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        Pattern ours = Pattern.compile(Globs.toRegex(glob));
        for (String path : PATHS) {
            assertThat(ours.matcher(path).matches())
                    .as("%s against %s", glob, path)
                    .isEqualTo(jdk.matches(Path.of(path)));
        }
    }
}
