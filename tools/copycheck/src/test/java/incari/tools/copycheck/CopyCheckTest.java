package incari.tools.copycheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CopyCheckTest {

    private static final String VENDOR = """
            package com.example.vendor;

            import java.util.List;

            public class Engine {
                private int speed;

                public int accelerate(int delta, List<Integer> history) {
                    int next = this.speed + delta;
                    if (next > 100) {
                        next = 100;
                    } else if (next < 0) {
                        next = 0;
                    }
                    history.add(next);
                    this.speed = next;
                    return next;
                }
            }
            """;

    @Test
    void flagsACopiedMethodEvenWhenReformattedAndCommented() {
        String incari = """
                package incari.platform;

                import java.util.List;
                import java.util.ArrayList;

                final class Pasted {
                    // Looks innocent after a reformat.
                    public int accelerate(int delta, List<Integer> history) { int next = this.speed + delta;
                        if (next > 100) { next = 100; }
                        else if (next < 0) { next = 0; }
                        history.add(next); this.speed = next; return next; }
                }
                """;

        List<CopyCheck.Finding> findings = CopyCheck.of(List.of(VENDOR), 20).check("Pasted.java", incari);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.file()).isEqualTo("Pasted.java");
            assertThat(f.firstLine()).isEqualTo(8);
            assertThat(f.lastLine()).isEqualTo(12);
            assertThat(f.tokens()).isGreaterThanOrEqualTo(40);
        });
    }

    @Test
    void ignoresSharedImportsAndShortCommonRuns() {
        String incari = """
                package incari.platform;

                import java.util.List;

                final class Fresh {
                    int clamp(int value) {
                        return Math.max(0, Math.min(100, value));
                    }

                    public int accelerate(int delta, List<Integer> history) {
                        return clamp(delta);
                    }
                }
                """;

        assertThat(CopyCheck.of(List.of(VENDOR), 20).check("Fresh.java", incari))
                .isEmpty();
    }

    @Test
    void reportsSeparateRunsSeparately() {
        String block = IntStream.range(0, 30).mapToObj(i -> "call" + i + "();").collect(Collectors.joining("\n"));
        String vendor = "class V { void a() {\n" + block + "\n} }";
        String incari =
                "class I { void a() {\n" + block + "\nfresh(); other(); stuff(); here(); and(); more(); code();\n"
                        + "fillers(); to(); break(); the(); run(); apart(); now();\n" + block + "\n} }";

        List<CopyCheck.Finding> findings = CopyCheck.of(List.of(vendor), 20).check("I.java", incari);

        assertThat(findings).hasSize(2);
    }

    @Test
    void tokenizerSkipsCommentsAndKeepsLiteralsWhole() {
        List<String> tokens =
                JavaTokens.tokenize("""
                        package a.b; import c.D;
                        /* block
                           comment */ String s = "a // not a comment"; // tail
                        char c = '\\''; double d = 1.5e3; var t = \"""
                            text block
                            \""";
                        """).stream().map(JavaTokens.Token::text).toList();

        assertThat(tokens)
                .containsSubsequence("String", "s", "=", "\"a // not a comment\"", ";")
                .contains("'\\''", "1.5e3")
                .doesNotContain("package", "import", "block", "tail");
        assertThat(tokens.stream().filter(t -> t.startsWith("\"\"\""))).hasSize(1);
    }
}
