package incari.framework.hud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnchorTest {

    @Test
    void sidesMeasureOffsetsInward() {
        assertThat(Anchor.TOP_LEFT.x(2, 50, 320)).isEqualTo(2);
        assertThat(Anchor.TOP_RIGHT.x(2, 50, 320)).isEqualTo(268);
        assertThat(Anchor.BOTTOM_RIGHT.y(4, 9, 240)).isEqualTo(227);
    }

    @Test
    void centersMeasureOffsetsFromTheMiddle() {
        assertThat(Anchor.CENTER.x(0, 50, 320)).isEqualTo(135);
        assertThat(Anchor.TOP.x(-10, 50, 320)).isEqualTo(125);
        assertThat(Anchor.LEFT.y(0, 10, 240)).isEqualTo(115);
    }

    @Test
    void elementsStayOnScreen() {
        assertThat(Anchor.TOP_LEFT.x(400, 50, 320)).isEqualTo(270);
        assertThat(Anchor.TOP_RIGHT.x(-30, 50, 320)).isEqualTo(270);
        assertThat(Anchor.TOP_LEFT.x(-5, 50, 320)).isZero();
        // Bigger than the screen: pinned to its start.
        assertThat(Anchor.BOTTOM_RIGHT.x(0, 400, 320)).isZero();
    }

    @Test
    void theNearestAnchorIsTheThirdTheMiddleIsIn() {
        assertThat(Anchor.nearest(2, 2, 50, 9, 320, 240)).isEqualTo(Anchor.TOP_LEFT);
        assertThat(Anchor.nearest(135, 115, 50, 10, 320, 240)).isEqualTo(Anchor.CENTER);
        assertThat(Anchor.nearest(268, 227, 50, 9, 320, 240)).isEqualTo(Anchor.BOTTOM_RIGHT);
        assertThat(Anchor.nearest(130, 2, 50, 9, 320, 240)).isEqualTo(Anchor.TOP);
        // Bigger than the screen: still an anchor, by its middle.
        assertThat(Anchor.nearest(0, 0, 400, 300, 320, 240)).isEqualTo(Anchor.CENTER);
    }

    @Test
    void offsetsAreThePlacementsInverse() {
        for (Anchor anchor : Anchor.values()) {
            for (int start : new int[] {0, 3, 100, 135, 270}) {
                assertThat(anchor.x(anchor.offsetX(start, 50, 320), 50, 320)).isEqualTo(start);
                assertThat(anchor.y(anchor.offsetY(start % 191, 49, 240), 49, 240))
                        .isEqualTo(start % 191);
            }
        }
        assertThat(Anchor.TOP_RIGHT.offsetX(268, 50, 320)).isEqualTo(2);
    }
}
