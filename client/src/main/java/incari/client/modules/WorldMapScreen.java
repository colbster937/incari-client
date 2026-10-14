package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import incari.client.ui.Icons;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.Shapes;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.game.PlayerView;
import incari.framework.hud.Shape;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Setting;
import incari.framework.waypoint.Waypoint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * The world map (ADR 0051): the land the minimap has seen ({@link MapMemory}) across the whole
 * screen, dragged about with the mouse and zoomed with the wheel round the pointer. The player is
 * an arrow, each waypoint its icon and name; a click on one edits it, a right-click elsewhere makes
 * a new one there. Center comes back to the player; the map's key or Escape closes it. Opened by the
 * Minimap module's key (M).
 *
 * <p>Each region of 512 by 512 blocks it shows is a texture of its own, made from its remembered
 * colors when it first comes into view (two a frame at most) and again when the minimap has drawn
 * more of it, at most twice a second. They're let go when the map closes.
 */
final class WorldMapScreen extends Screen {

    /** GUI units a block, the least and the most. */
    private static final float MIN_SCALE = 1 / 8.0F;

    private static final float MAX_SCALE = 8;

    /** Each notch of the wheel zooms by this much. */
    private static final float ZOOM_STEP = 1.25F;

    /** Region textures made a frame at most: each is a quarter of a million texels. */
    private static final int UPLOADS_A_FRAME = 2;

    /** Region textures kept at most while the map's open. */
    private static final int TEXTURES = 48;

    /** How long a missing region's absence is believed before its file is looked for again. */
    private static final long MISSING_MILLIS = 2000;

    /** The land nobody's seen. */
    private static final int UNSEEN = 0xFF0E1319;

    /** The lines between regions, faint. */
    private static final int GRID = 0x14FFFFFF;

    private static final int BAR_HEIGHT = 32;

    private static final Component TITLE = Component.literal("World map");

    private final @Nullable Screen parent;
    private final ModuleSystem system;
    private final Map<MapMemory.Key, RegionTexture> textures = new HashMap<>();
    private final Map<MapMemory.Key, Long> missing = new HashMap<>();
    private final Set<MapMemory.Key> shown = new HashSet<>();

    private double centerX;
    private double centerZ;
    private float scale = 1;
    private boolean centered;
    private boolean dragging;
    private boolean dragged;
    private double pressX;
    private double pressY;
    private @Nullable NativeImage staging;
    private long frame;

    /** A region's texture, and which of its revisions it shows. */
    private static final class RegionTexture {
        final GpuTexture texture;
        final GpuTextureView view;
        int revision = -1;
        long madeAt;
        long usedAt;

        RegionTexture(GpuTexture texture, GpuTextureView view) {
            this.texture = texture;
            this.view = view;
        }
    }

    WorldMapScreen(@Nullable Screen parent, ModuleSystem system) {
        super(TITLE);
        this.parent = parent;
        this.system = system;
    }

    @Override
    protected void init() {
        PlayerView player = system.game().player();
        if (!centered && player != null) {
            centerX = player.x();
            centerZ = player.z();
            centered = true;
        }
        int y = 6;
        int right = width - 8;
        var done = Ui.button(CommonComponents.GUI_DONE, 60, this::onClose);
        done.setPosition(right - 60, y);
        addRenderableWidget(done);
        Component listLabel = Component.literal("Waypoints");
        int listWidth = 24 + font.width(listLabel) + 10;
        var list = Ui.menuButton(
                listLabel, Icons.GRID, listWidth, () -> minecraft.gui.setScreen(new WaypointListScreen(this, system)));
        list.setPosition(done.getX() - Theme.GAP - listWidth, y);
        addRenderableWidget(list);
        Component centerLabel = Component.literal("Center");
        int centerWidth = 24 + font.width(centerLabel) + 10;
        var center = Ui.menuButton(centerLabel, Icons.TARGET, centerWidth, this::centerOnPlayer);
        center.setPosition(list.getX() - Theme.GAP - centerWidth, y);
        addRenderableWidget(center);
    }

    private void centerOnPlayer() {
        PlayerView player = system.game().player();
        if (player != null) {
            centerX = player.x();
            centerZ = player.z();
        }
    }

    @Override
    public void tick() {
        super.tick();
        PlayerView player = system.game().player();
        if (player == null) {
            onClose();
            return;
        }
        // What's around the player is drawn, and so remembered, while the map is open, as if the
        // minimap were on screen.
        MapTerrain.keepDrawing(player.x(), player.z());
    }

    private String dimension() {
        PlayerView player = system.game().player();
        return player == null ? PlayerView.OVERWORLD : player.dimension();
    }

    /** The screen's x for a world x. */
    private float screenX(double x) {
        return (float) ((x - centerX) * scale + width / 2.0);
    }

    private float screenY(double z) {
        return (float) ((z - centerZ) * scale + height / 2.0);
    }

    private double worldX(double screenX) {
        return centerX + (screenX - width / 2.0) / scale;
    }

    private double worldZ(double screenY) {
        return centerZ + (screenY - height / 2.0) / scale;
    }

    // The map is the backdrop: opaque, under the buttons, and no blur over the world behind.
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        frame++;
        graphics.fill(0, 0, width, height, UNSEEN);
        String dimension = dimension();
        int regionX0 = Math.floorDiv((int) Math.floor(worldX(0)), MapMemory.REGION_BLOCKS);
        int regionX1 = Math.floorDiv((int) Math.floor(worldX(width)), MapMemory.REGION_BLOCKS);
        int regionZ0 = Math.floorDiv((int) Math.floor(worldZ(0)), MapMemory.REGION_BLOCKS);
        int regionZ1 = Math.floorDiv((int) Math.floor(worldZ(height)), MapMemory.REGION_BLOCKS);
        int uploads = 0;
        long now = Util.getMillis();
        shown.clear();
        for (int rz = regionZ0; rz <= regionZ1; rz++) {
            for (int rx = regionX0; rx <= regionX1; rx++) {
                MapMemory.Key key = new MapMemory.Key(dimension, rx, rz);
                Long absent = missing.get(key);
                if (absent != null && now - absent < MISSING_MILLIS) {
                    continue;
                }
                MapMemory.Region region = MapMemory.region(dimension, rx, rz, false);
                if (region == null) {
                    missing.put(key, now);
                    continue;
                }
                missing.remove(key);
                RegionTexture texture = textures.get(key);
                boolean stale = texture == null || (texture.revision != region.revision && now - texture.madeAt > 500);
                if (stale && uploads < UPLOADS_A_FRAME) {
                    texture = upload(key, region, texture, now);
                    uploads++;
                }
                if (texture != null) {
                    texture.usedAt = frame;
                    shown.add(key);
                    draw(graphics, texture, rx, rz);
                }
            }
        }
        // Region lines, faint, for a sense of distance.
        if (scale >= 0.25F) {
            for (int rx = regionX0; rx <= regionX1 + 1; rx++) {
                int x = Math.round(screenX((double) rx * MapMemory.REGION_BLOCKS));
                graphics.fill(x, 0, x + 1, height, GRID);
            }
            for (int rz = regionZ0; rz <= regionZ1 + 1; rz++) {
                int y = Math.round(screenY((double) rz * MapMemory.REGION_BLOCKS));
                graphics.fill(0, y, width, y + 1, GRID);
            }
        }
        forgetUnused();
    }

    private void draw(GuiGraphicsExtractor graphics, RegionTexture texture, int rx, int rz) {
        // The region's texels a block each, placed and scaled by the pose: fractional pans and zooms
        // stay exact, where a blit's whole-unit corners would leave seams.
        Matrix3x2f pose = new Matrix3x2f(graphics.pose())
                .translate(
                        screenX((double) rx * MapMemory.REGION_BLOCKS), screenY((double) rz * MapMemory.REGION_BLOCKS))
                .scale(scale);
        GpuSampler sampler = RenderSystem.getSamplerCache()
                .getSampler(
                        AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR,
                        FilterMode.NEAREST,
                        false);
        minecraft
                .gameRenderer
                .gameRenderState()
                .guiRenderState
                .addGuiElement(new BlitRenderState(
                        RenderPipelines.GUI_TEXTURED,
                        TextureSetup.singleTexture(texture.view, sampler),
                        pose,
                        0,
                        0,
                        MapMemory.REGION_BLOCKS,
                        MapMemory.REGION_BLOCKS,
                        0,
                        1,
                        0,
                        1,
                        0xFFFFFFFF,
                        null));
    }

    /** The region's texture made, or made again, from its colors. */
    private RegionTexture upload(MapMemory.Key key, MapMemory.Region region, @Nullable RegionTexture kept, long now) {
        RegionTexture texture = kept;
        if (texture == null) {
            GpuDevice device = RenderSystem.getDevice();
            int size = MapMemory.REGION_BLOCKS;
            GpuTexture made = device.createTexture(
                    () -> "Incari world map " + key.x() + "," + key.z(),
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    size,
                    size,
                    1,
                    1);
            texture = new RegionTexture(made, device.createTextureView(made));
            textures.put(key, texture);
        }
        NativeImage image = staging;
        if (image == null) {
            image = new NativeImage(MapMemory.REGION_BLOCKS, MapMemory.REGION_BLOCKS, true);
            staging = image;
        }
        for (int cz = 0; cz < MapMemory.REGION_CHUNKS; cz++) {
            for (int cx = 0; cx < MapMemory.REGION_CHUNKS; cx++) {
                int[] chunk = region.chunk(cx, cz);
                if (chunk == null) {
                    image.fillRect(cx * 16, cz * 16, 16, 16, 0);
                    continue;
                }
                for (int i = 0; i < 256; i++) {
                    image.setPixel(cx * 16 + (i & 15), cz * 16 + (i >> 4), chunk[i]);
                }
            }
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.texture, image);
        texture.revision = region.revision;
        texture.madeAt = now;
        return texture;
    }

    /** Over {@link #TEXTURES}, textures not shown this frame go, the least lately shown first. */
    private void forgetUnused() {
        while (textures.size() > TEXTURES) {
            MapMemory.Key oldest = null;
            long oldestUse = Long.MAX_VALUE;
            for (Map.Entry<MapMemory.Key, RegionTexture> entry : textures.entrySet()) {
                if (!shown.contains(entry.getKey()) && entry.getValue().usedAt < oldestUse) {
                    oldest = entry.getKey();
                    oldestUse = entry.getValue().usedAt;
                }
            }
            if (oldest == null) {
                return;
            }
            close(textures.remove(oldest));
        }
    }

    private static void close(@Nullable RegionTexture texture) {
        if (texture != null) {
            texture.view.close();
            texture.texture.close();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Waypoint hovered = drawWaypoints(graphics, mouseX, mouseY);
        drawPlayer(graphics);
        // The bar along the top: the title and the dimension, the buttons on the right.
        Panels.darkPanel(
                graphics,
                -Theme.PANEL_RADIUS,
                -Theme.PANEL_RADIUS,
                width + 2 * Theme.PANEL_RADIUS,
                BAR_HEIGHT + Theme.PANEL_RADIUS,
                1.0F);
        Shapes.draw(graphics, Shape.DIAMOND, 16, BAR_HEIGHT / 2.0F, 9, 0, Theme.ACCENT);
        graphics.text(font, TITLE, 26, BAR_HEIGHT / 2 - 4, Theme.TEXT, false);
        graphics.text(
                font,
                WaypointListScreen.dimensionName(dimension()),
                30 + font.width(TITLE),
                BAR_HEIGHT / 2 - 4,
                Theme.MUTED,
                false);
        // Along the bottom: where the pointer is, and how to use the map.
        String where = "X " + (int) Math.floor(worldX(mouseX)) + "   Z " + (int) Math.floor(worldZ(mouseY)) + "   ·   "
                + zoomText();
        String hint = hovered != null
                ? "Click to edit " + hovered.name()
                : "Drag to move  ·  Scroll to zoom  ·  Right-click to add a waypoint";
        int barY = height - 18;
        int whereWidth = font.width(where) + 12;
        RoundRects.fill(graphics, 6, barY, whereWidth, 14, Theme.RADIUS, Theme.FLOATING);
        graphics.text(font, where, 12, barY + 3, Theme.TEXT, false);
        // On a narrow screen the hint is cut short of the position, not drawn over it.
        hint = Ui.fit(font, hint, width - 6 - (6 + whereWidth + 6) - 12);
        int hintWidth = font.width(hint) + 12;
        RoundRects.fill(graphics, width - 6 - hintWidth, barY, hintWidth, 14, Theme.RADIUS, Theme.FLOATING);
        graphics.text(font, hint, width - hintWidth, barY + 3, Theme.MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /** "1 block = 2 px"-like: how many blocks a GUI unit, or units a block, as words a player reads. */
    private String zoomText() {
        int percent = Math.round(scale * 100);
        return "Zoom " + percent + "%";
    }

    /** The player: an arrow the way they face, ringed so it's found at a glance. */
    private void drawPlayer(GuiGraphicsExtractor graphics) {
        PlayerView player = system.game().player();
        if (player == null) {
            return;
        }
        float x = screenX(player.x());
        float y = screenY(player.z());
        incari.client.ui.Circles.disc(graphics, x, y, 9, 0x8C0E1319);
        incari.client.ui.Circles.ring(graphics, x, y, 9, 1.25F, Theme.ACCENT);
        Shapes.draw(graphics, Shape.ARROW, x, y, 11, player.heading(), Theme.TEXT);
    }

    /**
     * The dimension's shown waypoints: every icon, and the names on tags where they don't cover one
     * already written (the hovered one's first), so a crowd zoomed out stays readable. Returns the one
     * under the pointer, or null.
     */
    private @Nullable Waypoint drawWaypoints(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String dimension = dimension();
        Waypoint hovered = waypointAt(mouseX, mouseY);
        tags.clear();
        if (hovered != null) {
            tag(graphics, hovered, true);
        }
        for (Waypoint waypoint : system.waypoints().all()) {
            if (!waypoint.isVisible() || !waypoint.dimension().equals(dimension) || waypoint == hovered) {
                continue;
            }
            float x = screenX(waypoint.x() + 0.5);
            float y = screenY(waypoint.z() + 0.5);
            if (x < -60 || x > width + 60 || y < -20 || y > height + 20) {
                continue;
            }
            Shapes.draw(graphics, waypoint.icon(), x, y, 10, 0, waypoint.argb());
            tag(graphics, waypoint, false);
        }
        if (hovered != null) {
            // On top of the rest.
            Shapes.draw(
                    graphics,
                    hovered.icon(),
                    screenX(hovered.x() + 0.5),
                    screenY(hovered.z() + 0.5),
                    12,
                    0,
                    hovered.argb());
        }
        return hovered;
    }

    /** The tags written this frame, as left, top, right, bottom. */
    private final List<int[]> tags = new java.util.ArrayList<>();

    /** {@code waypoint}'s name on a tag above its icon, unless that covers a tag already written. */
    private void tag(GuiGraphicsExtractor graphics, Waypoint waypoint, boolean hot) {
        String name = waypoint.name();
        int tagWidth = font.width(name) + 8;
        int tagX = Math.round(screenX(waypoint.x() + 0.5) - tagWidth / 2.0F);
        int tagY = Math.round(screenY(waypoint.z() + 0.5) - 20);
        for (int[] other : tags) {
            if (tagX < other[2] + 2
                    && tagX + tagWidth > other[0] - 2
                    && tagY < other[3] + 1
                    && tagY + 12 > other[1] - 1) {
                return;
            }
        }
        tags.add(new int[] {tagX, tagY, tagX + tagWidth, tagY + 12});
        RoundRects.fill(graphics, tagX, tagY, tagWidth, 12, Theme.RADIUS, hot ? Theme.FLOATING : Theme.BACKGROUND);
        if (hot) {
            RoundRects.outline(graphics, tagX, tagY, tagWidth, 12, Theme.RADIUS, waypoint.argb());
        }
        graphics.text(font, name, tagX + 4, tagY + 2, Theme.TEXT, false);
    }

    /** The shown waypoint whose icon is under {@code x, y}, nearest first; null if none. */
    private @Nullable Waypoint waypointAt(double x, double y) {
        String dimension = dimension();
        Waypoint best = null;
        double bestDistance = 8 * 8;
        for (Waypoint waypoint : system.waypoints().all()) {
            if (!waypoint.isVisible() || !waypoint.dimension().equals(dimension)) {
                continue;
            }
            double dx = screenX(waypoint.x() + 0.5) - x;
            double dy = screenY(waypoint.z() + 0.5) - y;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                best = waypoint;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.y() < BAR_HEIGHT) {
            return false;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true;
            dragged = false;
            pressX = event.x();
            pressY = event.y();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            Waypoint waypoint = waypointAt(event.x(), event.y());
            if (waypoint != null) {
                minecraft.gui.setScreen(new WaypointEditScreen(this, system, waypoint, false));
            } else {
                addWaypointAt(event.x(), event.y());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            if (Math.abs(event.x() - pressX) + Math.abs(event.y() - pressY) > 3) {
                dragged = true;
            }
            centerX -= dx / scale;
            centerZ -= dy / scale;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false;
            if (!dragged) {
                Waypoint waypoint = waypointAt(event.x(), event.y());
                if (waypoint != null) {
                    minecraft.gui.setScreen(new WaypointEditScreen(this, system, waypoint, false));
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        // Round the pointer: the block under it stays under it.
        double x = worldX(mouseX);
        double z = worldZ(mouseY);
        float zoomed = (float) (scale * Math.pow(ZOOM_STEP, scrollY));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, zoomed));
        centerX = x - (mouseX - width / 2.0) / scale;
        centerZ = z - (mouseY - height / 2.0) / scale;
        return true;
    }

    /**
     * A new waypoint at the block under the pointer, in the editor: at the surface's height where
     * the chunk is loaded, else the player's.
     */
    private void addWaypointAt(double screenX, double screenY) {
        PlayerView player = system.game().player();
        if (player == null || minecraft.level == null) {
            return;
        }
        int x = (int) Math.floor(worldX(screenX));
        int z = (int) Math.floor(worldZ(screenY));
        int y = (int) Math.floor(player.y());
        if (minecraft.level.hasChunk(x >> 4, z >> 4)) {
            y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        Waypoint draft = WaypointListScreen.draft(system, x, y, z, player.dimension());
        minecraft.gui.setScreen(new WaypointEditScreen(this, system, draft, true));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // The map's own key closes it, as the inventory's key closes the inventory.
        Module minimap = system.module("minimap");
        Setting<?> setting = minimap == null ? null : minimap.settings().get("world-map");
        if (setting instanceof KeySetting key
                && key.get().isBound()
                && key.get().equals(GameKeys.fromGame(InputConstants.getKey(event)))) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_SPACE) {
            centerOnPlayer();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        for (RegionTexture texture : textures.values()) {
            close(texture);
        }
        textures.clear();
        NativeImage image = staging;
        if (image != null) {
            image.close();
            staging = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
