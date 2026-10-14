package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReleasesTest {

    @Test
    void aLaterPartIsNewer() {
        assertThat(Releases.newer("0.2.0", "0.1.0")).isTrue();
        assertThat(Releases.newer("0.10.0", "0.9.3")).isTrue();
        assertThat(Releases.newer("1.0", "0.99.99")).isTrue();
        assertThat(Releases.newer("0.1.1", "0.1")).isTrue();
    }

    @Test
    void theSameOrOlderIsNot() {
        assertThat(Releases.newer("0.1.0", "0.1.0")).isFalse();
        assertThat(Releases.newer("0.1", "0.1.0")).isFalse();
        assertThat(Releases.newer("0.1.0", "0.2.0")).isFalse();
    }

    @Test
    void anythingElseIsNeverNewer() {
        assertThat(Releases.valid("1.2.3-beta")).isNull();
        assertThat(Releases.valid("")).isNull();
        assertThat(Releases.valid("1.2.3.4.5")).isNull();
        assertThat(Releases.valid("v1.0")).isNull();
        assertThat(Releases.newer("2.0-rc1", "1.0")).isFalse();
        assertThat(Releases.newer("2.0", "dev")).isFalse();
        assertThat(Releases.newer(null, "1.0")).isFalse();
    }
}
