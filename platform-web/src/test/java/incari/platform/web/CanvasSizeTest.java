package incari.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The canvas's size in CSS and device pixels, as resizes, zoom and density changes report it. */
class CanvasSizeTest {

    private static final CanvasSize HD = new CanvasSize(1280, 720, 1280, 720, 1);

    @Test
    void theExactDevicePixelSizeWinsWhereTheBrowserHasOne() {
        // 1.25 density (a 125% Windows scale): 1001.6 device pixels, which the browser rounds its way.
        CanvasSize size = CanvasSize.measure(801.28, 600, 1001, 750, 1.25, HD);
        assertThat(size).isEqualTo(new CanvasSize(801, 600, 1001, 750, 1.25));
    }

    @Test
    void withoutDevicePixelsTheSizeIsTheCssSizeTimesTheDensity() {
        assertThat(CanvasSize.measure(640, 360, 0, 0, 2, HD)).isEqualTo(new CanvasSize(640, 360, 1280, 720, 2));
        assertThat(CanvasSize.measure(333.5, 200.25, 0, 0, 1.5, HD)).isEqualTo(new CanvasSize(334, 200, 500, 300, 1.5));
    }

    @Test
    void aDensityChangeAloneChangesOnlyThePixels() {
        // Moving the window to a denser display, or zooming the page: the same CSS box.
        CanvasSize zoomed = CanvasSize.measure(1280, 720, 0, 0, 1.5, HD);
        assertThat(zoomed.width()).isEqualTo(1280);
        assertThat(zoomed.pixelWidth()).isEqualTo(1920);
        assertThat(zoomed.density()).isEqualTo(1.5);
    }

    @Test
    void anUnchangedMeasurementKeepsTheExactPixels() {
        // 801 x 1.25 is 1001.25, but the browser said 1002: the page snapped the canvas to pixels its way.
        CanvasSize exact = new CanvasSize(801, 600, 1002, 750, 1.25);
        assertThat(CanvasSize.measure(801, 600, 0, 0, 1.25, exact)).isEqualTo(exact);
    }

    @Test
    void anEmptyOrHiddenCanvasKeepsTheSizeBefore() {
        assertThat(CanvasSize.measure(0, 600, 0, 0, 1, HD)).isSameAs(HD);
        assertThat(CanvasSize.measure(800, 0, 0, 0, 1, HD)).isSameAs(HD);
        assertThat(CanvasSize.measure(Double.NaN, 600, 0, 0, 1, HD)).isSameAs(HD);
    }

    @Test
    void aMissingDensityCountsAsOne() {
        assertThat(CanvasSize.measure(800, 600, 0, 0, 0, HD)).isEqualTo(new CanvasSize(800, 600, 800, 600, 1));
    }

    @Test
    void tinySizesAreAtLeastOnePixel() {
        assertThat(CanvasSize.measure(0.2, 0.2, 0, 0, 1, HD)).isEqualTo(new CanvasSize(1, 1, 1, 1, 1));
    }

    @Test
    void theInitialSizeComesFromTheFirstMeasurement() {
        assertThat(CanvasSize.initial(1280, 720, 2)).isEqualTo(new CanvasSize(1280, 720, 2560, 1440, 2));
        assertThat(CanvasSize.initial(0, 0, 2)).isEqualTo(new CanvasSize(1, 1, 1, 1, 2));
    }
}
