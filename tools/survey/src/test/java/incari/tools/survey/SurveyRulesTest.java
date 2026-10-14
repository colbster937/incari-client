package incari.tools.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurveyRulesTest {

    private static Finding finding(Finding.Kind kind, String symbol, String caller) {
        return new Finding(kind, symbol, symbol, caller, "m()V", 1, null);
    }

    @Test
    void globsDistinguishOneSegmentFromAnything() {
        assertThat(SurveyRules.glob("java.nio.file.*")
                        .matcher("java.nio.file.Files")
                        .matches())
                .isTrue();
        assertThat(SurveyRules.glob("java.nio.file.*")
                        .matcher("java.nio.file.spi.FileSystemProvider")
                        .matches())
                .isFalse();
        assertThat(SurveyRules.glob("java.nio.**")
                        .matcher("java.nio.file.spi.FileSystemProvider#x")
                        .matches())
                .isTrue();
        assertThat(SurveyRules.glob("java.lang.Thread#set*")
                        .matcher("java.lang.Thread#setPriority")
                        .matches())
                .isTrue();
        assertThat(SurveyRules.glob("a$b").matcher("a$b").matches()).isTrue();
    }

    @Test
    void firstMatchingRuleWinsAndAllSelectorsMustMatch() {
        SurveyRules rules = SurveyRules.parse(
                List.of(
                        "delete  caller=net.minecraft.util.profiling.**  Profiler",
                        "stub    kind=missing-method symbol=java.lang.Thread#setPriority  Thread priorities",
                        "shim    symbol=java.lang.Thread  Threads as coroutines"),
                "rules.txt");

        Finding priorityInProfiler = finding(
                Finding.Kind.MISSING_METHOD, "java.lang.Thread#setPriority", "net.minecraft.util.profiling.Profiler");
        Finding priority = finding(Finding.Kind.MISSING_METHOD, "java.lang.Thread#setPriority", "net.minecraft.Util");
        Finding reflectivePriority =
                finding(Finding.Kind.REFLECTION, "java.lang.Thread#setPriority", "net.minecraft.Util");

        assertThat(rules.classify(priorityInProfiler, "missing-method", "minecraft")
                        .reason())
                .isEqualTo("Profiler");
        assertThat(rules.classify(priority, "missing-method", "minecraft").reason())
                .isEqualTo("Thread priorities");
        // A pattern without '#' matches every member of that class.
        assertThat(rules.classify(reflectivePriority, "reflection", "minecraft").reason())
                .isEqualTo("Threads as coroutines");
        assertThat(rules.classify(finding(Finding.Kind.MISSING_METHOD, "java.util.X#y", "a.B"), "missing-method", "x"))
                .isNull();
    }

    @Test
    void originSelectorMatchesTheCallersJar() {
        SurveyRules rules = SurveyRules.parse(List.of("shim origin=guava kind=host-jdk  Guava's JDK internals"), "r");
        Finding f = finding(Finding.Kind.HOST_JDK, "sun.misc.Unsafe#getUnsafe", "com.google.common.X");
        assertThat(rules.classify(f, "host-jdk", "guava")).isNotNull();
        assertThat(rules.classify(f, "host-jdk", "minecraft")).isNull();
    }

    @Test
    void libraryRulesMatchByPrefixAndEveryLibraryNeedsOne() {
        SurveyRules rules = SurveyRules.parse(
                List.of(
                        "library io.netty:netty-buffer:  keep     Pure Java",
                        "library io.netty:               replace  WebSocket transport"),
                "r");
        assertThat(rules.library("io.netty:netty-buffer:4.2.16.Final").action())
                .isEqualTo(SurveyRules.LibraryAction.KEEP);
        assertThat(rules.library("io.netty:netty-handler:4.2.16.Final").action())
                .isEqualTo(SurveyRules.LibraryAction.REPLACE);
        assertThatThrownBy(() -> rules.library("org.example:new:1.0"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("org.example:new:1.0");
    }

    @Test
    void malformedLinesNameTheirLocation() {
        assertThatThrownBy(() -> SurveyRules.parse(List.of("fix symbol=a.B  reason"), "r"))
                .hasMessageContaining("r:1");
        assertThatThrownBy(() -> SurveyRules.parse(List.of("stub symbol=a.B"), "r"))
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> SurveyRules.parse(List.of("stub nope=a.B  x"), "r"))
                .hasMessageContaining("unknown selector");
        assertThatThrownBy(() -> SurveyRules.parse(List.of("library a:"), "r"))
                .hasMessageContaining("expected: library");
    }

    @Test
    void theCheckedInRulesFileParses() throws Exception {
        SurveyRules rules =
                SurveyRules.load(Path.of(System.getProperty("user.dir")).resolve("rules.txt"));
        assertThat(rules.libraries()).isNotEmpty();
        assertThat(rules.triage()).isNotEmpty();
    }
}
