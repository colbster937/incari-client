package incari.client.credits;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CreditsTest {

    @Test
    void creditsIncariAndViaInEveryBuild() {
        assertThat(Credits.all()).extracting(Credit::name).startsWith("Incari Client", "ViaVersion", "ViaBackwards");
    }

    @Test
    void namesViaVersionsTheBuildBundles() {
        // The versions the build was compiled against, so the source links name the right release.
        assertThat(Credits.all())
                .filteredOn(credit -> credit.name().startsWith("Via"))
                .allSatisfy(credit -> assertThat(credit.version()).matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    void addsWhatABuildContainsAfterTheCommonEntries() {
        Credit added = new Credit(
                "Example",
                null,
                "Someone",
                "MIT",
                URI.create("https://example.com/license"),
                URI.create("https://example.com/source"));
        Credits.add(added);
        assertThat(Credits.all()).last().isEqualTo(added);
    }

    @Test
    void linksAreHttps() {
        assertThat(Credits.all()).allSatisfy(credit -> {
            assertThat(credit.source().getScheme()).isEqualTo("https");
            assertThat(credit.licenseText().getScheme()).isEqualTo("https");
        });
    }

    @Test
    void titleNamesTheVersionWhenThereIsOne() {
        URI uri = URI.create("https://example.com");
        assertThat(new Credit("A", "1.0", "x", "MIT", uri, uri).title()).isEqualTo("A 1.0");
        assertThat(new Credit("A", null, "x", "MIT", uri, uri).title()).isEqualTo("A");
    }
}
