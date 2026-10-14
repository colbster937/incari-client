package incari.client.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.serialization.JsonOps;
import incari.client.testing.TestPlatform;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link PackedUnihex} gives back every glyph's lines and edges exactly as the game's objects hold them. */
class PackedUnihexTest {

    /** Where {@code ./incari assets} keeps the game's Unicode font (asset index of 26.3). */
    private static final Path UNIFONT = Path.of(
            System.getProperty("incari.repoRoot", "."),
            "work/cache/assets/objects/cc/ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd");

    @BeforeAll
    static void platform() {
        TestPlatform.install();
    }

    @AfterEach
    void off() {
        PackedUnihex.setEnabled(false);
    }

    /** A glyph as the game's record holds one: its lines and edges. */
    private record TestGlyph(UnihexProvider.LineData contents, int left, int right) implements UnbakedGlyph {
        @Override
        public com.mojang.blaze3d.font.GlyphInfo info() {
            throw new UnsupportedOperationException();
        }

        @Override
        public net.minecraft.client.gui.font.glyphs.BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
            throw new UnsupportedOperationException();
        }
    }

    /** Lines of a given width, the bits at the top of each int as the game keeps them. */
    private static final class TestLines implements UnihexProvider.LineData {
        private final int[] lines;
        private final int bitWidth;

        TestLines(int[] lines, int bitWidth) {
            this.lines = lines;
            this.bitWidth = bitWidth;
        }

        @Override
        public int line(int index) {
            return lines[index];
        }

        @Override
        public int bitWidth() {
            return bitWidth;
        }
    }

    @Test
    void everyWidthReadsBackBitForBit() {
        Random random = new Random(26_3);
        CodepointMap<TestGlyph> glyphs = new CodepointMap<>(TestGlyph[]::new, TestGlyph[][]::new);
        int[] widths = {8, 16, 24, 32};
        for (int i = 0; i < 20_000; i++) {
            int codepoint = random.nextInt(0x110000);
            int width = widths[random.nextInt(widths.length)];
            int[] lines = new int[16];
            for (int line = 0; line < 16; line++) {
                lines[line] = width == 32 ? random.nextInt() : (random.nextInt() >>> (32 - width)) << (32 - width);
            }
            glyphs.put(
                    codepoint,
                    new TestGlyph(new TestLines(lines, width), random.nextInt(32) - 8, random.nextInt(40) - 8));
        }
        glyphs.put(0x10FFFF, new TestGlyph(new TestLines(new int[16], 16), 0, 15));

        PackedUnihex packed = PackedUnihex.pack(glyphs, TestGlyph::contents, TestGlyph::left, TestGlyph::right);

        assertThat(packed.codepoints()).isEqualTo(glyphs.keySet());
        glyphs.forEach((codepoint, expected) -> {
            TestGlyph actual = packed.get(codepoint, TestGlyph::new);
            assertThat(actual).isNotNull();
            assertSame(
                    codepoint,
                    expected.contents(),
                    expected.left(),
                    expected.right(),
                    actual.contents(),
                    actual.left(),
                    actual.right());
        });
        assertThat(packed.get(0x110000, TestGlyph::new)).isNull();
        assertThat(packed.get(-1, TestGlyph::new)).isNull();
    }

    @Test
    void theGamesUnicodeFontPacksExactly() throws Exception {
        Assumptions.assumeTrue(Files.exists(UNIFONT), "The Unicode font isn't in work/cache/assets (./incari assets)");
        PackedUnihex.setEnabled(false);
        UnihexProvider game = load();
        PackedUnihex.setEnabled(true);
        UnihexProvider packed = load();

        assertThat(packed.getSupportedGlyphs()).isEqualTo(game.getSupportedGlyphs());
        assertThat(game.getSupportedGlyphs()).hasSizeGreaterThan(50_000);
        for (int codepoint : game.getSupportedGlyphs()) {
            UnbakedGlyph expected = game.getGlyph(codepoint);
            UnbakedGlyph actual = packed.getGlyph(codepoint);
            assertThat(actual).isNotNull();
            assertSame(
                    codepoint,
                    contents(expected),
                    edge(expected, "left"),
                    edge(expected, "right"),
                    contents(actual),
                    edge(actual, "left"),
                    edge(actual, "right"));
            assertThat(actual.info().getAdvance()).isEqualTo(expected.info().getAdvance());
        }
    }

    private static void assertSame(
            int codepoint,
            UnihexProvider.LineData expected,
            int expectedLeft,
            int expectedRight,
            UnihexProvider.LineData actual,
            int left,
            int right) {
        assertThat(actual.bitWidth()).as("width of %x", codepoint).isEqualTo(expected.bitWidth());
        for (int line = 0; line < 16; line++) {
            assertThat(actual.line(line)).as("line %d of %x", line, codepoint).isEqualTo(expected.line(line));
        }
        assertThat(actual.calculateWidth()).isEqualTo(expected.calculateWidth());
        assertThat(left).as("left of %x", codepoint).isEqualTo(expectedLeft);
        assertThat(right).as("right of %x", codepoint).isEqualTo(expectedRight);
    }

    /** The game's unihex provider for its Unicode font, read from the asset cache. */
    private static UnihexProvider load() throws Exception {
        UnihexProvider.Definition definition = UnihexProvider.Definition.CODEC
                .codec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"hex_file\": \"minecraft:font/unifont.zip\"}"))
                .getOrThrow();
        Method loadData = UnihexProvider.Definition.class.getDeclaredMethod("loadData", InputStream.class);
        loadData.setAccessible(true);
        try (InputStream in = Files.newInputStream(UNIFONT)) {
            return (UnihexProvider) loadData.invoke(definition, in);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    /** The game's glyph record's lines, read through its accessor. */
    private static UnihexProvider.LineData contents(UnbakedGlyph glyph) throws ReflectiveOperationException {
        return (UnihexProvider.LineData) component(glyph, "contents");
    }

    private static int edge(UnbakedGlyph glyph, String name) throws ReflectiveOperationException {
        return (Integer) component(glyph, name);
    }

    private static Object component(UnbakedGlyph glyph, String name) throws ReflectiveOperationException {
        for (RecordComponent component : glyph.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(glyph);
            }
        }
        throw new NoSuchFieldException(name + " in " + glyph.getClass());
    }
}
