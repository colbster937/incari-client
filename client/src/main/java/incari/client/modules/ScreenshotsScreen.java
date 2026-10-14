package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.platform.api.PlatformShell;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The screenshot gallery (Screenshot Manager, ADR 0054): the screenshots folder's pictures as a grid
 * of thumbnails, newest first, oldest first or by name. A click opens one large, with its name to
 * change, and buttons to delete, download (in the browser: a page can't show its files) or copy it,
 * and arrows to the next. Select mode picks several to download as one zip.
 *
 * <p>Thumbnails are made from the files as they scroll into view, one a frame: a Chromebook decodes
 * a full-size PNG in tens of milliseconds, and a frame mustn't take more than one.
 */
final class ScreenshotsScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A thumbnail's width in pixels: enough for the grid's cells at GUI scale 3. */
    private static final int THUMB_PIXELS = 192;

    private static final int CELL_WIDTH = 96;
    private static final int CELL_HEIGHT = 54;
    private static final int LABEL = 11;
    private static final int GAP = 8;
    private static final int TOP = 40;
    private static final int BOTTOM = 34;

    /** The grid's buttons at their widest. */
    private static final int FOOTER_BUTTON = 90;

    /** The narrowest the viewer's name field gets before its row goes on two lines. */
    private static final int MIN_NAME_FIELD = 80;

    /** How the grid is ordered. */
    private enum Sort {
        NEWEST("Newest first"),
        OLDEST("Oldest first"),
        NAME("By name");

        private final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    /** One picture: its file and, once made, its thumbnail. */
    private static final class Shot {
        Path path;
        String name;
        long modified;

        @Nullable
        Identifier thumb;

        int thumbWidth;
        int thumbHeight;
        boolean failed;
        boolean selected;

        Shot(Path path, String name, long modified) {
            this.path = path;
            this.name = name;
            this.modified = modified;
        }
    }

    private final @Nullable Screen parent;
    private final Path folder;
    private final List<Shot> shots = new ArrayList<>();
    private final PlatformShell shell = ClientPlatform.get().shell();

    private Sort sort = Sort.NEWEST;
    private boolean selecting;
    private double scroll;
    private int columns = 1;
    private int gridLeft;

    /** The height the buttons take at the bottom: {@link #BOTTOM}, more when they're on two rows. */
    private int bottom = BOTTOM;

    /** The picture open large, or null for the grid. */
    private @Nullable Shot viewing;

    private @Nullable Identifier full;
    private int fullWidth;
    private int fullHeight;
    private @Nullable EditBox nameField;
    private @Nullable String status;
    private int made;

    ScreenshotsScreen(@Nullable Screen parent, Path gameDirectory) {
        super(Component.literal("Screenshots"));
        this.parent = parent;
        this.folder = gameDirectory.resolve("screenshots");
        reload();
    }

    private void reload() {
        releaseThumbs();
        shots.clear();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(path -> path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".png"))
                        .forEach(path ->
                                shots.add(new Shot(path, path.getFileName().toString(), modified(path))));
            } catch (IOException e) {
                LOGGER.warn("Couldn't list {}: {}", folder, e.toString());
            }
        }
        order();
    }

    private static long modified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (IOException | RuntimeException e) {
            // The browser's files keep no times on some paths: the name (a date) sorts them then.
            return 0;
        }
    }

    private void order() {
        Comparator<Shot> byName = Comparator.comparing(shot -> shot.name.toLowerCase(Locale.ROOT));
        Comparator<Shot> byTime =
                Comparator.<Shot>comparingLong(shot -> shot.modified).thenComparing(byName);
        shots.sort(
                switch (sort) {
                    case NEWEST -> byTime.reversed();
                    case OLDEST -> byTime;
                    case NAME -> byName;
                });
    }

    @Override
    protected void init() {
        if (viewing != null) {
            initViewer();
        } else {
            initGrid();
        }
    }

    private void initGrid() {
        columns = Math.max(1, (this.width - GAP * 2) / (CELL_WIDTH + GAP));
        gridLeft = (this.width - (columns * (CELL_WIDTH + GAP) - GAP)) / 2;
        bottom = BOTTOM;
        int y = this.height - BOTTOM + 7;
        List<Button> footer = new ArrayList<>();
        footer.add(Ui.button(Component.literal(sort.label), FOOTER_BUTTON, () -> {
            sort = switch (sort) {
                case NEWEST -> Sort.OLDEST;
                case OLDEST -> Sort.NAME;
                case NAME -> Sort.NEWEST;
            };
            order();
            rebuildWidgets();
        }));
        footer.add(Ui.button(Component.literal(selecting ? "Done selecting" : "Select"), FOOTER_BUTTON, () -> {
            selecting = !selecting;
            if (!selecting) {
                shots.forEach(shot -> shot.selected = false);
            }
            rebuildWidgets();
        }));
        if (selecting) {
            Button download =
                    Ui.button(Component.literal("Download " + selectedCount()), FOOTER_BUTTON, this::downloadSelected);
            download.active = selectedCount() > 0;
            footer.add(download);
        } else if (shell.showsFolders()) {
            footer.add(Ui.button(Component.literal("Open folder"), FOOTER_BUTTON, () -> {
                try {
                    Files.createDirectories(folder);
                } catch (IOException e) {
                    LOGGER.warn("Couldn't make {}: {}", folder, e.toString());
                }
                shell.openPath(folder.toAbsolutePath().toString());
            }));
        }
        footer.add(Ui.button(CommonComponents.GUI_DONE, FOOTER_BUTTON, this::onClose));
        // Narrower buttons where four at their widest don't fit (a small window at a large GUI scale).
        int buttonWidth =
                Math.min(FOOTER_BUTTON, (this.width - GAP * 2 - (footer.size() - 1) * Theme.GAP) / footer.size());
        for (Button button : footer) {
            button.setWidth(buttonWidth);
        }
        row(footer, y);
    }

    /** Adds {@code widgets} side by side, centered, at {@code y}. */
    private void row(List<? extends net.minecraft.client.gui.components.AbstractWidget> widgets, int y) {
        int total = -Theme.GAP;
        for (var widget : widgets) {
            total += widget.getWidth() + Theme.GAP;
        }
        int x = (this.width - total) / 2;
        for (var widget : widgets) {
            widget.setPosition(x, y);
            addRenderableWidget(widget);
            x += widget.getWidth() + Theme.GAP;
        }
    }

    private void initViewer() {
        Shot shot = viewing;
        if (shot == null) {
            return;
        }
        EditBox field = Ui.textField(this.font, Component.literal("Name"), 140, 120);
        String base = shot.name.substring(0, shot.name.length() - ".png".length());
        field.setValue(base);
        nameField = field;
        Button rename = Ui.button(Component.literal("Rename"), 60, this::rename);
        List<Button> actions = List.of(
                Ui.button(Component.literal(shell.showsFolders() ? "Show" : "Download"), 70, () -> download(shot)),
                Ui.button(Component.literal("Copy"), 50, () -> copy(shot)),
                Ui.button(Component.literal("Delete"), 55, () -> confirmDelete(shot)),
                Ui.button(CommonComponents.GUI_BACK, 55, this::closeViewer));
        int room = this.width - GAP * 2;
        int others = rename.getWidth() + Theme.GAP;
        for (Button action : actions) {
            others += action.getWidth() + Theme.GAP;
        }
        int y = this.height - BOTTOM + 7;
        if (room - others >= MIN_NAME_FIELD) {
            // One row: the name field takes what the buttons leave, up to its full width.
            field.setWidth(Math.min(field.getWidth(), room - others));
            bottom = BOTTOM;
            List<net.minecraft.client.gui.components.AbstractWidget> row = new ArrayList<>();
            row.add(field);
            row.add(rename);
            row.addAll(actions);
            row(row, y);
        } else {
            // Too narrow for one: the name and Rename on a row above the others.
            field.setWidth(Math.min(field.getWidth(), room - rename.getWidth() - Theme.GAP));
            bottom = BOTTOM + Theme.CONTROL_HEIGHT + Theme.GAP;
            row(List.of(field, rename), y - Theme.CONTROL_HEIGHT - Theme.GAP);
            row(actions, y);
        }
        int arrowY = TOP + (this.height - TOP - bottom) / 2 - 10;
        addRenderableWidget(Ui.button(Component.literal("<"), 20, () -> step(-1)))
                .setPosition(6, arrowY);
        addRenderableWidget(Ui.button(Component.literal(">"), 20, () -> step(1)))
                .setPosition(this.width - 26, arrowY);
    }

    private int selectedCount() {
        int count = 0;
        for (Shot shot : shots) {
            if (shot.selected) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        made = 0;
        Shot shot = viewing;
        if (shot != null) {
            extractViewer(graphics, shot);
        } else {
            extractGrid(graphics, mouseX, mouseY);
        }
        String note = status;
        if (note != null) {
            Ui.caption(graphics, this.font, Component.literal(note), this.width / 2, this.height - bottom - 10);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void extractGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Ui.title(graphics, this.font, this.title, this.width / 2, 12);
        Ui.caption(
                graphics,
                this.font,
                Component.literal(
                        shots.isEmpty()
                                ? "No screenshots yet. Press F2 in a world to take one."
                                : shots.size()
                                        + (shots.size() == 1 ? " screenshot" : " screenshots")
                                        + (selecting ? " - click to pick, then download" : "")),
                this.width / 2,
                24);
        int bottom = this.height - this.bottom;
        graphics.enableScissor(0, TOP - 4, this.width, bottom);
        for (int i = 0; i < shots.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (y + CELL_HEIGHT + LABEL < TOP - 4 || y > bottom) {
                continue;
            }
            Shot shot = shots.get(i);
            boolean hovered = mouseX >= x
                    && mouseX < x + CELL_WIDTH
                    && mouseY >= y
                    && mouseY < y + CELL_HEIGHT
                    && mouseY >= TOP - 4
                    && mouseY < bottom;
            Panels.darkPanel(graphics, x - 2, y - 2, CELL_WIDTH + 4, CELL_HEIGHT + LABEL + 4, 1);
            thumb(shot);
            Identifier thumb = shot.thumb;
            if (thumb != null) {
                fit(graphics, thumb, shot.thumbWidth, shot.thumbHeight, x, y, CELL_WIDTH, CELL_HEIGHT);
            } else {
                Ui.caption(
                        graphics,
                        this.font,
                        Component.literal(shot.failed ? "Can't read" : "..."),
                        x + CELL_WIDTH / 2,
                        y + CELL_HEIGHT / 2 - 4);
            }
            if (shot.selected) {
                RoundRects.outline(
                        graphics, x - 2, y - 2, CELL_WIDTH + 4, CELL_HEIGHT + LABEL + 4, Theme.RADIUS, Theme.ACCENT);
                RoundRects.fill(graphics, x + CELL_WIDTH - 12, y + 2, 10, 10, 5, Theme.ACCENT);
            } else if (hovered) {
                RoundRects.outline(
                        graphics, x - 2, y - 2, CELL_WIDTH + 4, CELL_HEIGHT + LABEL + 4, Theme.RADIUS, Theme.TEXT);
            }
            String label = this.font.plainSubstrByWidth(shot.name.substring(0, shot.name.length() - 4), CELL_WIDTH);
            graphics.text(this.font, label, x, y + CELL_HEIGHT + 2, hovered ? Theme.TEXT : Theme.MUTED, false);
        }
        graphics.disableScissor();
    }

    private void extractViewer(GuiGraphicsExtractor graphics, Shot shot) {
        Ui.title(graphics, this.font, Component.literal(shot.name), this.width / 2, 12);
        Ui.caption(
                graphics,
                this.font,
                Component.literal((shots.indexOf(shot) + 1) + " of " + shots.size()),
                this.width / 2,
                24);
        Identifier image = full;
        if (image == null && !shot.failed) {
            // Once: a picture that can't be read isn't read again every frame.
            loadFull(shot);
            image = full;
        }
        int areaX = 32;
        int areaWidth = this.width - 64;
        int areaHeight = this.height - TOP - bottom - 6;
        if (image != null) {
            fit(graphics, image, fullWidth, fullHeight, areaX, TOP, areaWidth, areaHeight);
        } else {
            Ui.caption(
                    graphics, this.font, Component.literal("Can't read this picture"), this.width / 2, this.height / 2);
        }
    }

    /** Draws a picture as large as fits {@code w} by {@code h}, its shape kept, centered. */
    private static void fit(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int texWidth,
            int texHeight,
            int x,
            int y,
            int w,
            int h) {
        float scale = Math.min(w / (float) texWidth, h / (float) texHeight);
        int drawWidth = Math.max(1, Math.round(texWidth * scale));
        int drawHeight = Math.max(1, Math.round(texHeight * scale));
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                x + (w - drawWidth) / 2,
                y + (h - drawHeight) / 2,
                0,
                0,
                drawWidth,
                drawHeight,
                texWidth,
                texHeight,
                texWidth,
                texHeight);
    }

    private int cellX(int index) {
        return gridLeft + index % columns * (CELL_WIDTH + GAP);
    }

    private int cellY(int index) {
        return TOP + index / columns * (CELL_HEIGHT + LABEL + GAP) - (int) scroll;
    }

    /** Makes {@code shot}'s thumbnail if it has none, one a frame. */
    private void thumb(Shot shot) {
        if (shot.thumb != null || shot.failed || made > 0) {
            return;
        }
        made++;
        try (InputStream in = Files.newInputStream(shot.path);
                NativeImage image = NativeImage.read(in)) {
            int width = Math.min(THUMB_PIXELS, image.getWidth());
            int height = Math.max(1, Math.round(image.getHeight() * (width / (float) image.getWidth())));
            NativeImage small = new NativeImage(width, height, false);
            // Each thumbnail pixel the average of the 2 by 2 nearest: smoother than one sample.
            float step = image.getWidth() / (float) width;
            for (int ty = 0; ty < height; ty++) {
                for (int tx = 0; tx < width; tx++) {
                    int sx = Math.min(image.getWidth() - 2, (int) (tx * step));
                    int sy = Math.min(image.getHeight() - 2, (int) (ty * step));
                    small.setPixel(
                            tx,
                            ty,
                            average(
                                    image.getPixel(sx, sy),
                                    image.getPixel(sx + 1, sy),
                                    image.getPixel(sx, sy + 1),
                                    image.getPixel(sx + 1, sy + 1)));
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(
                    "incari", "screenshot_thumb/" + Integer.toHexString(System.identityHashCode(shot)));
            minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Screenshot thumbnail", small));
            shot.thumb = id;
            shot.thumbWidth = width;
            shot.thumbHeight = height;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}: {}", shot.path, e.toString());
            shot.failed = true;
        }
    }

    private static int average(int a, int b, int c, int d) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
            result |= (sum / 4) << shift;
        }
        return result;
    }

    private void loadFull(Shot shot) {
        releaseFull();
        try (InputStream in = Files.newInputStream(shot.path)) {
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath("incari", "screenshot_full");
            minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Screenshot", image));
            full = id;
            fullWidth = image.getWidth();
            fullHeight = image.getHeight();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}: {}", shot.path, e.toString());
            shot.failed = true;
        }
    }

    private void releaseFull() {
        Identifier id = full;
        if (id != null) {
            minecraft.getTextureManager().release(id);
            full = null;
        }
    }

    private void releaseThumbs() {
        for (Shot shot : shots) {
            Identifier id = shot.thumb;
            if (id != null) {
                minecraft.getTextureManager().release(id);
                shot.thumb = null;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (viewing != null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (event.y() < TOP - 4 || event.y() >= this.height - bottom) {
            return false;
        }
        for (int i = 0; i < shots.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (event.x() >= x && event.x() < x + CELL_WIDTH && event.y() >= y && event.y() < y + CELL_HEIGHT + LABEL) {
                Shot shot = shots.get(i);
                if (selecting) {
                    shot.selected = !shot.selected;
                    rebuildWidgets();
                } else {
                    open(shot);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (viewing != null) {
            if (scrollY != 0) {
                step(scrollY > 0 ? -1 : 1);
            }
            return true;
        }
        int rows = (shots.size() + columns - 1) / columns;
        int content = rows * (CELL_HEIGHT + LABEL + GAP);
        int room = this.height - TOP - bottom;
        scroll = Math.max(0, Math.min(Math.max(0, content - room), scroll - scrollY * 24));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (viewing != null) {
            EditBox field = nameField;
            boolean typing = field != null && field.isFocused();
            if (!typing && event.key() == InputConstants.KEY_LEFT) {
                step(-1);
                return true;
            }
            if (!typing && event.key() == InputConstants.KEY_RIGHT) {
                step(1);
                return true;
            }
            if (typing && event.key() == InputConstants.KEY_RETURN) {
                rename();
                return true;
            }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                closeViewer();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void open(Shot shot) {
        viewing = shot;
        status = null;
        releaseFull();
        rebuildWidgets();
    }

    private void closeViewer() {
        viewing = null;
        status = null;
        releaseFull();
        rebuildWidgets();
    }

    private void step(int by) {
        Shot shot = viewing;
        if (shot == null || shots.isEmpty()) {
            return;
        }
        int index = Math.floorMod(shots.indexOf(shot) + by, shots.size());
        open(shots.get(index));
    }

    private void rename() {
        Shot shot = viewing;
        EditBox field = nameField;
        if (shot == null || field == null) {
            return;
        }
        String wanted = cleanName(field.getValue());
        if (wanted.isEmpty()) {
            status = "A name needs a letter or a number";
            return;
        }
        String fileName = wanted + ".png";
        if (fileName.equals(shot.name)) {
            return;
        }
        Path target = folder.resolve(fileName);
        if (Files.exists(target)) {
            status = "There's already a screenshot called " + wanted;
            return;
        }
        try {
            Files.move(shot.path, target);
            shot.path = target;
            shot.name = fileName;
            status = "Renamed";
            order();
        } catch (IOException e) {
            LOGGER.warn("Couldn't rename {}: {}", shot.path, e.toString());
            status = "Couldn't rename it";
        }
    }

    /** What's left of {@code name} for a file: letters, digits, spaces, dots, dashes, underscores. */
    static String cleanName(String name) {
        StringBuilder clean = new StringBuilder();
        for (char c : name.strip().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                clean.append(c);
            }
        }
        String result = clean.toString().strip();
        while (result.toLowerCase(Locale.ROOT).endsWith(".png")) {
            result = result.substring(0, result.length() - 4);
        }
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        return result.length() > 100 ? result.substring(0, 100) : result;
    }

    private void confirmDelete(Shot shot) {
        minecraft.gui.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        delete(shot);
                    }
                    minecraft.gui.setScreen(this);
                },
                Component.literal("Delete " + shot.name + "?"),
                Component.literal("This can't be undone.")));
    }

    private void delete(Shot shot) {
        int index = shots.indexOf(shot);
        try {
            Files.deleteIfExists(shot.path);
        } catch (IOException e) {
            LOGGER.warn("Couldn't delete {}: {}", shot.path, e.toString());
            status = "Couldn't delete it";
            return;
        }
        Identifier thumb = shot.thumb;
        if (thumb != null) {
            minecraft.getTextureManager().release(thumb);
        }
        shots.remove(shot);
        releaseFull();
        viewing = shots.isEmpty() ? null : shots.get(Math.min(index, shots.size() - 1));
        status = null;
    }

    private void download(Shot shot) {
        if (shell.showsFolders()) {
            shell.openPath(shot.path.toAbsolutePath().toString());
            return;
        }
        try {
            shell.download(shot.name, Files.readAllBytes(shot.path));
            status = "Downloading " + shot.name;
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", shot.path, e.toString());
            status = "Couldn't read it";
        }
    }

    private void copy(Shot shot) {
        status = ModuleScreenshots.copy(shot.path) ? "Copied to the clipboard" : "This platform can't copy pictures";
    }

    private void downloadSelected() {
        List<Shot> chosen = new ArrayList<>();
        for (Shot shot : shots) {
            if (shot.selected) {
                chosen.add(shot);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        if (chosen.size() == 1 || shell.showsFolders()) {
            chosen.forEach(this::download);
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // PNGs are compressed already: stored, not deflated again.
            zip.setLevel(0);
            for (Shot shot : chosen) {
                zip.putNextEntry(new ZipEntry(shot.name));
                zip.write(Files.readAllBytes(shot.path));
                zip.closeEntry();
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't zip the screenshots: {}", e.toString());
            status = "Couldn't zip them";
            return;
        }
        shell.download("screenshots.zip", bytes.toByteArray());
        status = "Downloading " + chosen.size() + " screenshots";
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        releaseFull();
        releaseThumbs();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
