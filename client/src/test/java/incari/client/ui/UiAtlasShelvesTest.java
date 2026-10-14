package incari.client.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UiAtlasShelvesTest {

    @Test
    void placesAlongAShelfThenStartsTheNextBelowItsTallest() {
        UiAtlas.Shelves shelves = new UiAtlas.Shelves(16);
        long a = shelves.place(6, 4);
        long b = shelves.place(6, 7);
        long c = shelves.place(6, 2);
        assertThat(UiAtlas.Shelves.x(a)).isZero();
        assertThat(UiAtlas.Shelves.y(a)).isZero();
        assertThat(UiAtlas.Shelves.x(b)).isEqualTo(6);
        assertThat(UiAtlas.Shelves.y(b)).isZero();
        // No room left on the first shelf: the second starts below its tallest image, 7.
        assertThat(UiAtlas.Shelves.x(c)).isZero();
        assertThat(UiAtlas.Shelves.y(c)).isEqualTo(7);
    }

    @Test
    void saysWhenThePageIsFullAndKeepsWhatItHad() {
        UiAtlas.Shelves shelves = new UiAtlas.Shelves(16);
        assertThat(shelves.place(16, 10)).isNotEqualTo(UiAtlas.Shelves.FULL);
        assertThat(shelves.place(4, 7)).isEqualTo(UiAtlas.Shelves.FULL);
        // A smaller image still fits in what's left.
        long small = shelves.place(4, 6);
        assertThat(UiAtlas.Shelves.y(small)).isEqualTo(10);
        assertThat(shelves.place(17, 1)).isEqualTo(UiAtlas.Shelves.FULL);
    }
}
