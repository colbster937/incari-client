package incari.client.modules;

import incari.client.ui.Circles;
import incari.client.ui.RoundRects;
import incari.client.ui.Shapes;
import incari.client.ui.SmoothFont;
import incari.client.ui.Theme;
import incari.framework.ModuleSystem;
import incari.framework.event.Crosshair;
import incari.framework.event.Events;
import incari.framework.game.EffectView;
import incari.framework.game.GamePart;
import incari.framework.game.ItemView;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.Shape;
import incari.framework.hud.Tone;
import incari.framework.hud.WorldOverlay;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Modules' HUD elements, drawn with the game's HUD. The hook sits after the HUD's first stratum, so
 * in the browser they go into the HUD's texture with the rest and are drawn only on its refreshes
 * (ADR 0030). Colors come from the {@link Theme} by tone. Render thread.
 *
 * <p>What must keep up with the camera is drawn every frame by a second hook, with the crosshair in
 * the HUD's first stratum (ADR 0051): the overlay event's listeners (a waypoint's label, with
 * {@link #project} to place it) and the live HUD elements (the minimap).
 */
public final class ModuleHud implements WorldOverlay {

    /** One canvas for every frame: the HUD is drawn up to a few dozen times a second. */
    private static final ModuleHud CANVAS = new ModuleHud();

    /** Whether the HUD editor is open: it draws the elements itself, above its blur. */
    private static boolean hidden;

    private @Nullable GuiGraphicsExtractor graphics;
    private @Nullable Font font;
    private boolean editing;

    /** This frame's camera, for {@link #project}: the view's rotation, then its projection. */
    private final Matrix4f view = new Matrix4f();

    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f point = new Vector4f();
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    /**
     * Text widths measured lately, by the string's hash: elements measure the same strings while
     * sizing and again while drawing, and a measure walks every glyph. Forgotten when the font
     * changes, and every {@link #WIDTH_DRAWS} draws, in case a resource reload changed the glyphs.
     */
    private static final int WIDTHS = 128;

    private static final int WIDTH_DRAWS = 64;
    private final @Nullable String[] widthOf = new String[WIDTHS];
    private final int[] widthValue = new int[WIDTHS];
    private int widthGeneration;
    private int widthDraws;

    /** What the world's projection added to the camera's last frame: bobbing, tilt, nausea. */
    private static final Matrix4f WORLD_WARP = new Matrix4f();

    private ModuleHud() {}

    /**
     * Hook ({@code GameRenderer.renderLevel}, once the world's projection is made): what it adds to
     * the camera's own, for the overlay's projection. Hot path: nothing allocates.
     */
    public static void worldProjection(Matrix4fc camera, Matrix4fc world) {
        if (WORLD_WARP.set(camera).invert().mul(world).isFinite()) {
            return;
        }
        WORLD_WARP.identity();
    }

    /**
     * Hook ({@code Hud.extractRenderState}, after the crosshair, before the HUD's first stratum
     * ends): what's drawn every frame, the overlay and the live elements. Not while the HUD editor
     * is open: it draws the elements itself.
     */
    public static void extractLive(GuiGraphicsExtractor graphics) {
        ModuleSystem system = IncariModules.current();
        if (hidden || system == null || !system.hasLiveHud()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!camera.initialized || minecraft.level == null) {
            return;
        }
        CANVAS.view.set(camera.viewRotationMatrix);
        // The world is drawn with view bobbing, the hurt tilt and nausea's warp on top of the
        // camera's projection (GameRenderer.renderLevel), after the HUD is extracted: the last
        // frame's, a frame old, keeps tags on what they mark.
        CANVAS.viewProjection.set(camera.projectionMatrix).mul(WORLD_WARP).mul(camera.viewRotationMatrix);
        CANVAS.cameraX = camera.pos.x;
        CANVAS.cameraY = camera.pos.y;
        CANVAS.cameraZ = camera.pos.z;
        CANVAS.graphics = graphics;
        CANVAS.font = minecraft.font;
        CANVAS.checkWidths();
        try {
            system.events().fireOverlay(CANVAS);
            system.drawLiveHud(CANVAS);
        } finally {
            CANVAS.graphics = null;
            CANVAS.font = null;
        }
    }

    /** One object for every frame's crosshair. */
    private static final Crosshair CROSSHAIR = new Crosshair();

    /**
     * Hook ({@code Hud.extractCrosshair}, where the game draws its crosshair): modules draw theirs.
     *
     * @return whether a module drew one, so the game's is left out
     */
    public static boolean crosshair(GuiGraphicsExtractor graphics) {
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.CROSSHAIR) == 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CANVAS.graphics = graphics;
        CANVAS.font = minecraft.font;
        try {
            int scale = Math.max(1, minecraft.getWindow().getGuiScale());
            // The screen's pixel middle, which the GUI's own middle misses by half a unit on odd sizes.
            int middleX = minecraft.getWindow().getWidth() / 2;
            int middleY = minecraft.getWindow().getHeight() / 2;
            float centerX = middleX / (float) scale;
            float centerY = middleY / (float) scale;
            CROSSHAIR.reset(
                    CANVAS,
                    centerX,
                    centerY,
                    1.0F / scale,
                    system.game().attackStrength(),
                    system.game().onTarget());
            system.events().fireCrosshair(CROSSHAIR);
        } finally {
            CANVAS.graphics = null;
            CANVAS.font = null;
        }
        return true;
    }

    /** Hook ({@code Hud.extractRenderState}, before the chat): draws the modules' elements. */
    public static void extract(GuiGraphicsExtractor graphics) {
        ModuleSystem system = IncariModules.current();
        if (hidden || system == null || !system.hasHud()) {
            return;
        }
        CANVAS.graphics = graphics;
        CANVAS.font = Minecraft.getInstance().font;
        CANVAS.checkWidths();
        try {
            system.drawHud(CANVAS);
        } finally {
            CANVAS.graphics = null;
            CANVAS.font = null;
        }
    }

    /** The HUD editor's: the HUD leaves the modules' elements out while it's open. */
    static void hide(boolean editorOpen) {
        hidden = editorOpen;
    }

    /**
     * The HUD editor's: the canvas over {@code graphics}, in editing mode ({@link
     * HudCanvas#editing()}), until {@link #end()}.
     */
    static HudCanvas begin(GuiGraphicsExtractor graphics) {
        CANVAS.graphics = graphics;
        CANVAS.font = Minecraft.getInstance().font;
        CANVAS.editing = true;
        CANVAS.checkWidths();
        return CANVAS;
    }

    /** Ends {@link #begin}. */
    static void end() {
        CANVAS.graphics = null;
        CANVAS.font = null;
        CANVAS.editing = false;
    }

    @Override
    public boolean editing() {
        return editing;
    }

    private GuiGraphicsExtractor graphics() {
        GuiGraphicsExtractor current = graphics;
        if (current == null) {
            throw new IllegalStateException("The modules' HUD is drawn only during the HUD's extraction");
        }
        return current;
    }

    private Font font() {
        Font current = font;
        if (current == null) {
            throw new IllegalStateException("The modules' HUD is drawn only during the HUD's extraction");
        }
        return current;
    }

    @Override
    public int screenWidth() {
        return graphics().guiWidth();
    }

    @Override
    public int screenHeight() {
        return graphics().guiHeight();
    }

    @Override
    public int textWidth(String text) {
        int slot = text.hashCode() & (WIDTHS - 1);
        String kept = widthOf[slot];
        if (text.equals(kept)) {
            return widthValue[slot];
        }
        int width = font().width(text);
        widthOf[slot] = text;
        widthValue[slot] = width;
        return width;
    }

    /** Before a draw: the widths are forgotten if the font changed, or now and then. */
    private void checkWidths() {
        int generation = SmoothFont.generation();
        if (generation != widthGeneration || ++widthDraws >= WIDTH_DRAWS) {
            widthGeneration = generation;
            widthDraws = 0;
            Arrays.fill(widthOf, null);
        }
    }

    @Override
    public int lineHeight() {
        return font().lineHeight;
    }

    @Override
    public void text(String text, int x, int y, Tone tone) {
        graphics().text(font(), text, x, y, Theme.color(tone), Theme.HUD_TEXT_SHADOW);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        graphics().text(font(), text, x, y, argb, Theme.HUD_TEXT_SHADOW);
    }

    @Override
    public void fill(int x, int y, int width, int height, Tone tone) {
        if (tone == Tone.BACKGROUND) {
            // A panel behind text: the look's tinted glass, rounded.
            RoundRects.fill(graphics(), x, y, width, height, Theme.RADIUS, Theme.BACKGROUND);
            return;
        }
        fill(x, y, width, height, Theme.color(tone));
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        // From the UI atlas, as circles, shapes and panels are: drawn together, not a draw call each switch.
        RoundRects.fill(graphics(), x, y, width, height, 0, argb);
    }

    @Override
    public void item(ItemView item, int x, int y, boolean decorations) {
        if (item.isEmpty() || !(item instanceof ModuleGame.Item view)) {
            return;
        }
        // With the player as its owner, as the hotbar draws it (a compass points, a bow shows its pull).
        graphics().item(view.stack, x, y);
        if (decorations) {
            graphics().itemDecorations(font(), view.stack, x, y);
        }
    }

    @Override
    public void effect(EffectView effect, int x, int y) {
        if (effect instanceof ModuleGame.Effect view) {
            graphics().blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(view.effect()), x, y, 18, 18);
        }
    }

    @Override
    public boolean hasPart(GamePart part) {
        return switch (part) {
            case SCOREBOARD -> GameParts.hasSidebar(editing);
        };
    }

    @Override
    public int partWidth(GamePart part) {
        return switch (part) {
            case SCOREBOARD -> GameParts.sidebarWidth(editing);
        };
    }

    @Override
    public int partHeight(GamePart part) {
        return switch (part) {
            case SCOREBOARD -> GameParts.sidebarHeight(editing);
        };
    }

    @Override
    public void part(GamePart part, int x, int y) {
        switch (part) {
            case SCOREBOARD -> GameParts.drawSidebar(graphics(), x, y, editing);
        }
    }

    @Override
    public void shape(Shape shape, float centerX, float centerY, float size, float rotation, Tone tone) {
        Shapes.draw(graphics(), shape, centerX, centerY, size, rotation, Theme.color(tone));
    }

    @Override
    public void shape(Shape shape, float centerX, float centerY, float size, float rotation, int argb) {
        Shapes.draw(graphics(), shape, centerX, centerY, size, rotation, argb);
    }

    @Override
    public void disc(float centerX, float centerY, float radius, Tone tone) {
        Circles.disc(graphics(), centerX, centerY, radius, Theme.color(tone));
    }

    @Override
    public void ring(float centerX, float centerY, float radius, float width, Tone tone) {
        Circles.ring(graphics(), centerX, centerY, radius, width, Theme.color(tone));
    }

    @Override
    public void disc(float centerX, float centerY, float radius, int argb) {
        Circles.disc(graphics(), centerX, centerY, radius, argb);
    }

    @Override
    public void ring(float centerX, float centerY, float radius, float width, int argb) {
        Circles.ring(graphics(), centerX, centerY, radius, width, argb);
    }

    @Override
    public void arc(float centerX, float centerY, float radius, float width, float fraction, int argb) {
        Circles.arc(graphics(), centerX, centerY, radius, width, fraction, argb);
    }

    @Override
    public void arc(float centerX, float centerY, float radius, float width, float fraction, Tone tone) {
        Circles.arc(graphics(), centerX, centerY, radius, width, fraction, Theme.color(tone));
    }

    @Override
    public void terrain(
            int x, int y, int size, boolean round, double centerX, double centerZ, float blocksAcross, float rotation) {
        MapTerrain.draw(graphics(), x, y, size, round, centerX, centerZ, blocksAcross, rotation);
    }

    @Override
    public boolean project(double x, double y, double z, float[] out) {
        float dx = (float) (x - cameraX);
        float dy = (float) (y - cameraY);
        float dz = (float) (z - cameraZ);
        float width = screenWidth();
        float height = screenHeight();
        viewProjection.transform(point.set(dx, dy, dz, 1.0F));
        out[2] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (point.w > 0.05F) {
            out[0] = (point.x / point.w * 0.5F + 0.5F) * width;
            out[1] = (0.5F - point.y / point.w * 0.5F) * height;
            return true;
        }
        // Behind: the way to turn, from the middle and far out past the edge. Across as it lies
        // (behind on the left is to the left), and down by how far behind it is, so straight behind
        // is straight down; at the view's sides this meets where points in front project.
        view.transform(point.set(dx, dy, dz, 1.0F));
        float across = point.x;
        float down = Math.max(0.0F, point.z);
        float length = (float) Math.sqrt(across * across + down * down);
        if (length < 1.0E-4F) {
            across = 0;
            down = 1;
            length = 1;
        }
        float far = width + height;
        out[0] = width / 2 + across / length * far;
        out[1] = height / 2 + down / length * far;
        return false;
    }

    @Override
    public double cameraX() {
        return cameraX;
    }

    @Override
    public double cameraY() {
        return cameraY;
    }

    @Override
    public double cameraZ() {
        return cameraZ;
    }

    @Override
    public void push(float x, float y, float scale) {
        graphics().pose().pushMatrix().translate(x, y).scale(scale);
    }

    @Override
    public void pop() {
        graphics().pose().popMatrix();
    }
}
