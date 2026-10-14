package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void asksForTheBuildSoThePageDoesNotWaitForAPick() {
        assertThat(PageQuery.withBuild("name=Player", "fast")).isEqualTo("name=Player&build=fast");
        assertThat(PageQuery.withBuild("", "compatible")).isEqualTo("build=compatible");
    }

    @Test
    void keepsABuildTheQueryNamesAlready() {
        assertThat(PageQuery.withBuild("name=Player&build=compatible", "fast"))
                .isEqualTo("name=Player&build=compatible");
    }

    @Test
    void addsNothingForAnExperimentWithOneModule() {
        assertThat(PageQuery.withBuild("name=Player", "")).isEqualTo("name=Player");
    }

    @Test
    void doesNotMistakeAnotherOptionEndingInBuildForOne() {
        assertThat(PageQuery.withBuild("rebuild=x", "fast")).isEqualTo("rebuild=x&build=fast");
    }
}
