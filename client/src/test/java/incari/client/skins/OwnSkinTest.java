package incari.client.skins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OwnSkinTest {

    @Test
    void listsEveryDefaultSkinInBothModelsOnce() {
        assertThat(OwnSkin.defaultChoices()).hasSize(18);
        assertThat(OwnSkin.defaultChoices()).extracting(OwnSkin.Choice::key).doesNotHaveDuplicates();
        assertThat(OwnSkin.defaultChoices())
                .extracting(OwnSkin.Choice::label)
                .startsWith("Steve", "Alex", "Ari", "Ari (Slim)")
                .endsWith("Steve (Slim)", "Alex (Wide)");
        assertThat(OwnSkin.defaultChoices()).noneMatch(OwnSkin.Choice::custom);
    }

    @Test
    void readsTheModelFromTheWideArmsColumns() {
        int[] wide = new int[64 * 64];
        Arrays.fill(wide, 0xFF336699);
        assertThat(OwnSkin.looksSlim(wide)).isFalse();

        int[] slim = wide.clone();
        for (int y = 20; y < 32; y++) {
            slim[y * 64 + 54] = 0;
            slim[y * 64 + 55] = 0;
        }
        assertThat(OwnSkin.looksSlim(slim)).isTrue();
    }

    @Test
    void namesAnAddedSkinAfterItsFile() {
        assertThat(OwnSkin.fileBase("My Skin.PNG")).isEqualTo("My Skin");
        assertThat(OwnSkin.fileBase("a/b?.png")).isEqualTo("a_b_");
        assertThat(OwnSkin.fileBase(".png")).isEqualTo("skin");
        assertThat(OwnSkin.fileBase("x".repeat(40) + ".png")).hasSize(32);
    }
}
