package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClasslibRenamerTest {

    @Test
    void mapsSubstitutesBackToTheirOriginals() {
        assertThat(ClasslibRenamer.original("incari.classlib.substitutes.java.nio.channels.TFileChannel"))
                .isEqualTo("java.nio.channels.FileChannel");
        assertThat(ClasslibRenamer.original("incari.classlib.substitutes.java.nio.channels.TFileChannel$MapMode"))
                .isEqualTo("java.nio.channels.FileChannel$MapMode");
        assertThat(ClasslibRenamer.original("incari.classlib.substitutes.org.slf4j.TLoggerFactory"))
                .isEqualTo("org.slf4j.LoggerFactory");
    }

    @Test
    void leavesEverythingElse() {
        assertThat(ClasslibRenamer.original("incari.classlib.nio.FileChannels"))
                .isEqualTo("incari.classlib.nio.FileChannels");
        assertThat(ClasslibRenamer.original("incari.classlib.substitutes.java.util.Helper"))
                .isEqualTo("incari.classlib.substitutes.java.util.Helper");
        assertThat(ClasslibRenamer.original("java.lang.Thread")).isEqualTo("java.lang.Thread");
    }
}
