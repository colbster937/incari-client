package incari.client.ui;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTexture;
import incari.platform.api.Platforms;
import incari.platform.api.fonts.FontFace;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A TrueType font drawn by the platform ({@code PlatformFonts}): FreeType on the desktop, the
 * browser's own text in the page, which has no FreeType for the game's provider. Every {@code ttf}
 * provider loads through here, Incari's font (Red Hat Display) and resource packs' alike.
 *
 * <p>Glyphs are drawn at the GUI scale, whatever the font file asks for: the game samples its font
 * texture pixel for pixel, so a glyph drawn at the scale it's shown at stays sharp where a smaller
 * one scaled up would show its pixels. When the GUI scale changes ({@code Minecraft.resizeGui}),
 * every glyph is drawn again at the new one.
 */
public final class SmoothFont implements GlyphProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The fonts loaded, to draw again at a new scale. Render thread after loading. */
    private static final List<SmoothFont> LOADED = new ArrayList<>();

    private static int guiScale = 2;

    /** Incari's own text font, the provider a theme's font replaces. */
    private static final Identifier MAIN = Identifier.fromNamespaceAndPath("incari", "red_hat_display.ttf");

    /** The theme's font: its file in Incari's pack, or null for the game's own pixel font. */
    private static volatile @Nullable String chosenFile = "red_hat_display.ttf";

    private static volatile float chosenScale = 1.0F;

    /** {@code Minecraft.updateFontOptions}: drops the game's copies of the glyphs. Render thread. */
    private static @Nullable Runnable reloadGlyphs;

    /** Counts the font's changes: text laid out before one is measured in the old font. */
    private static int generation;

    private byte[] data;
    private String name;
    private final float size;
    /** The theme font's size against Incari's own (fonts of one size in points differ in height). */
    private float scale = 1.0F;

    private final float shiftX;
    private final float shiftY;
    private IntSet codepoints;
    private final boolean main;
    private final CodepointMap<UnbakedGlyph> glyphs = new CodepointMap<>(UnbakedGlyph[]::new, UnbakedGlyph[][]::new);

    private float oversample;
    private @Nullable FontFace face;
    private boolean closed;

    private SmoothFont(
            byte[] data,
            String name,
            float size,
            float shiftX,
            float shiftY,
            IntSet codepoints,
            @Nullable FontFace face,
            boolean main) {
        this.data = data;
        this.name = name;
        this.size = size;
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        this.codepoints = codepoints;
        this.face = face;
        this.main = main;
        this.oversample = guiScale;
    }

    /**
     * {@code TrueTypeGlyphProviderDefinition.load}: the font at {@code location}, or null if the
     * platform can't draw fonts (the game's FreeType provider is tried then). Background thread: in
     * the browser this suspends while the page loads the font.
     */
    public static @Nullable GlyphProvider load(
            ResourceManager resources, Identifier location, float size, float shiftX, float shiftY, String skip)
            throws IOException {
        boolean main = location.equals(MAIN);
        String file = chosenFile;
        Identifier source = main && file != null ? Identifier.fromNamespaceAndPath("incari", file) : location;
        byte[] data;
        try (InputStream in = resources.open(source.withPrefix("font/"))) {
            data = in.readAllBytes();
        }
        guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        String name = faceName(source);
        float scale = main ? chosenScale : 1.0F;
        FontFace face = Platforms.current().fonts().open(data, name, size * scale * guiScale);
        if (face == null) {
            return null;
        }
        IntSet codepoints = FontCmap.codepoints(data);
        skip.codePoints().forEach(codepoints::remove);
        if (main && file == null) {
            // The game's pixel font chosen: this provider answers nothing, and the game's own,
            // under Incari's in the font's stack, draws every glyph.
            codepoints.clear();
        }
        SmoothFont font = new SmoothFont(data, name, size, shiftX, shiftY, codepoints, face, main);
        font.scale = scale;
        synchronized (LOADED) {
            LOADED.add(font);
        }
        LOGGER.info("Font {}: {} glyphs at {} px", location, codepoints.size(), size * guiScale);
        return font;
    }

    private static String faceName(Identifier location) {
        return "incari-" + location.getNamespace() + "-"
                + location.getPath().replace('/', '-').replace('.', '-');
    }

    /**
     * A theme's font ({@code ModuleGame.applyFont}): Incari's own text is drawn from {@code file} in
     * Incari's pack ({@code assets/incari/font/}) at {@code scale} times its size, or, for a null
     * file, in the game's pixel font. The file is read and opened on a background thread (in the
     * browser that suspends while the page loads it), then swapped in on the render thread, and the
     * game's glyphs are dropped. Game thread.
     */
    public static void useFont(@Nullable String file, float scale) {
        if (java.util.Objects.equals(file, chosenFile) && scale == chosenScale) {
            return;
        }
        chosenFile = file;
        chosenScale = scale;
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.util.Util.backgroundExecutor().execute(() -> {
            byte[] data = null;
            FontFace face = null;
            IntSet codepoints = null;
            String name = null;
            if (file != null) {
                Identifier source = Identifier.fromNamespaceAndPath("incari", file);
                try (InputStream in = minecraft.getResourceManager().open(source.withPrefix("font/"))) {
                    data = in.readAllBytes();
                } catch (IOException e) {
                    LOGGER.warn("Couldn't read the font {}: {}", file, e.toString());
                    return;
                }
                name = faceName(source);
                // Loads the font into the page; the provider opens it at its own size when swapped in.
                face = Platforms.current().fonts().open(data, name, 9.5F * scale * guiScale);
                if (face == null) {
                    return;
                }
                codepoints = FontCmap.codepoints(data);
            }
            byte[] loadedData = data;
            FontFace loadedFace = face;
            IntSet loadedCodepoints = codepoints;
            String loadedName = name;
            minecraft.execute(() -> {
                if (!java.util.Objects.equals(file, chosenFile) || scale != chosenScale) {
                    // Another font was chosen while this one loaded.
                    if (loadedFace != null) {
                        loadedFace.close();
                    }
                    return;
                }
                boolean any = false;
                synchronized (LOADED) {
                    for (SmoothFont font : LOADED) {
                        if (font.main) {
                            font.swap(loadedData, loadedName, loadedFace, loadedCodepoints, scale);
                            any = true;
                        }
                    }
                }
                Runnable reload = reloadGlyphs;
                if (any && reload != null) {
                    reload.run();
                }
                generation++;
                LOGGER.info("Text font: {}", file == null ? "the game's" : file);
            });
        });
    }

    /** Puts a theme's font in this provider's place ({@link #useFont}); a null face for the game's. */
    private synchronized void swap(
            byte @Nullable [] newData,
            @Nullable String newName,
            @Nullable FontFace newFace,
            @Nullable IntSet newCodepoints,
            float newScale) {
        if (closed) {
            if (newFace != null) {
                newFace.close();
            }
            return;
        }
        if (newFace == null || newData == null || newName == null || newCodepoints == null) {
            codepoints = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        } else {
            FontFace old = face;
            face = newFace;
            if (old != null) {
                old.close();
            }
            data = newData;
            name = newName;
            codepoints = newCodepoints;
            scale = newScale;
            // Opened at the size the main font's file asks for and the GUI scale of its load: open
            // it again at this provider's size and today's scale. The platform has the font loaded
            // now, so this doesn't suspend on the render thread.
            oversample = -1;
            rescale();
        }
        glyphs.clear();
    }

    /**
     * {@code Minecraft.resizeGui}: if the GUI scale changed, every font's glyphs are drawn again at
     * the new one, and {@code reload} drops the game's copies of them.
     */
    public static void guiScale(int scale, Runnable reload) {
        reloadGlyphs = reload;
        int newScale = Math.max(1, scale);
        if (newScale == guiScale) {
            return;
        }
        guiScale = newScale;
        boolean any;
        synchronized (LOADED) {
            any = !LOADED.isEmpty();
            for (SmoothFont font : LOADED) {
                font.rescale();
            }
        }
        if (any) {
            reload.run();
            generation++;
        }
    }

    /**
     * A number that changes whenever the text's font or its measures do (a theme's font, the GUI
     * scale): text cut or wrapped to a width, and kept, is laid out again when it differs. Render
     * thread.
     */
    public static int generation() {
        return generation;
    }

    private synchronized void rescale() {
        if (closed || oversample == guiScale) {
            return;
        }
        FontFace old = face;
        // Opening a font the platform has already loaded doesn't suspend, so this is safe on the
        // render thread.
        face = Platforms.current().fonts().open(data, name, size * scale * guiScale);
        if (old != null) {
            old.close();
        }
        oversample = guiScale;
        glyphs.clear();
    }

    @Override
    public synchronized @Nullable UnbakedGlyph getGlyph(int codepoint) {
        if (closed || !codepoints.contains(codepoint)) {
            return null;
        }
        UnbakedGlyph glyph = glyphs.get(codepoint);
        if (glyph == null) {
            glyph = draw(codepoint);
            if (glyph == null) {
                return null;
            }
            glyphs.put(codepoint, glyph);
        }
        return glyph;
    }

    private @Nullable UnbakedGlyph draw(int codepoint) {
        FontFace open = face;
        FontFace.Glyph drawn = open == null ? null : open.glyph(codepoint);
        if (drawn == null) {
            return null;
        }
        float advance = drawn.advance() / oversample;
        if (drawn.width() <= 0 || drawn.height() <= 0) {
            return new EmptyGlyph(advance);
        }
        return new Drawn(
                drawn, oversample, drawn.left() / oversample + shiftX, drawn.top() / oversample - shiftY, advance);
    }

    @Override
    public IntSet getSupportedGlyphs() {
        return codepoints;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (face != null) {
            face.close();
            face = null;
        }
        synchronized (LOADED) {
            LOADED.remove(this);
        }
    }

    /**
     * A smooth glyph's metrics. The game draws a shadow, and bold, by drawing the text again a
     * whole GUI unit away: crisp with its pixel font, but with an anti-aliased one that's two or
     * three screen pixels and reads as two copies of the text over each other (in lists, on grey
     * text above all). Half a unit, about a screen pixel, reads as a shadow and as bold.
     */
    private record SmoothInfo(float advance) implements GlyphInfo {

        private static final float OFFSET = 0.5F;

        @Override
        public float getAdvance() {
            return advance;
        }

        @Override
        public float getShadowOffset() {
            return OFFSET;
        }

        @Override
        public float getBoldOffset() {
            return OFFSET;
        }
    }

    /** A glyph drawn at one scale, uploaded to the font texture when the game first shows it. */
    private static final class Drawn implements UnbakedGlyph, GlyphBitmap {

        private final FontFace.Glyph glyph;
        private final float oversample;
        private final float bearingLeft;
        private final float bearingTop;
        private final GlyphInfo info;

        Drawn(FontFace.Glyph glyph, float oversample, float bearingLeft, float bearingTop, float advance) {
            this.glyph = glyph;
            this.oversample = oversample;
            this.bearingLeft = bearingLeft;
            this.bearingTop = bearingTop;
            this.info = new SmoothInfo(advance);
        }

        @Override
        public GlyphInfo info() {
            return info;
        }

        @Override
        public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
            return stitcher.stitch(info, this);
        }

        @Override
        public int getPixelWidth() {
            return glyph.width();
        }

        @Override
        public int getPixelHeight() {
            return glyph.height();
        }

        @Override
        public float getOversample() {
            return oversample;
        }

        @Override
        public float getBearingLeft() {
            return bearingLeft;
        }

        @Override
        public float getBearingTop() {
            return bearingTop;
        }

        @Override
        public void upload(int x, int y, GpuTexture texture) {
            try (NativeImage image =
                    new NativeImage(NativeImage.Format.LUMINANCE, glyph.width(), glyph.height(), false)) {
                // A view of the image's own memory: the coverage goes straight in, a byte a pixel.
                image.getPixelBytes().put(0, glyph.coverage());
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y);
            }
        }

        @Override
        public boolean isColored() {
            return false;
        }
    }
}
