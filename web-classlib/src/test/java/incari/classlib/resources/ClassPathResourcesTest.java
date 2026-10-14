package incari.classlib.resources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassPathResourcesTest {

    @Test
    void namesResolveAsClassGetResourceDoes() {
        assertThat(ClassPathResources.qualified(String.class, "/version.json")).isEqualTo("version.json");
        assertThat(ClassPathResources.qualified(String.class, "data.bin")).isEqualTo("java/lang/data.bin");
        assertThat(ClassPathResources.qualified(ClassPathResourcesTest.class, "a/b.txt"))
                .isEqualTo("incari/classlib/resources/a/b.txt");
    }

    @Test
    void missingResourcesAreNull() {
        assertThat(ClassPathResources.find("no/such/resource.json")).isNull();
        assertThat(ClassPathResources.url(null)).isNull();
        assertThat(ClassPathResources.open(null)).isNull();
    }
}
