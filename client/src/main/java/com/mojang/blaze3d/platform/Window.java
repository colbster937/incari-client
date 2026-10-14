package com.mojang.blaze3d.platform;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuBackend;
import incari.client.platform.ClientPlatform;
import incari.client.platform.DisplayModes;
import incari.platform.api.window.DisplayMode;
import incari.platform.api.window.IconImage;
import incari.platform.api.window.PlatformWindow;
import incari.platform.api.window.WindowSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.main.SilentInitException;
import net.minecraft.server.packs.PackMetadataResources;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's replacement for Mojang's window (patches/classes.txt). It keeps the game-facing state
 * and rules (sizes, GUI scale, the fullscreen request, crash details) and leaves the operating
 * system to a {@link PlatformWindow}: SDL on the desktop, the canvas in the browser.
 */
public final class Window implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MIN_WINDOW_WIDTH = WindowSpec.MIN_WIDTH;
    public static final int MIN_WINDOW_HEIGHT = WindowSpec.MIN_HEIGHT;
    /** The GUI's design size at scale 1: the scale grows while a whole multiple of it still fits. */
    public static final int BASE_WIDTH = 320;

    public static final int BASE_HEIGHT = 240;

    private final WindowEventHandler eventHandler;
    private final MonitorManager monitorManager;
    private final PlatformWindow platformWindow;

    /** Where the window goes back to when it leaves fullscreen. */
    private int restoreX;

    private int restoreY;
    private int restoreWidth;
    private int restoreHeight;

    private int screenX;
    private int screenY;
    private int screenWidth;
    private int screenHeight;
    private int pixelWidth;
    private int pixelHeight;
    private int scaleFactor;
    private int scaledWidth;
    private int scaledHeight;

    private Optional<VideoMode> preferredFullscreenVideoMode;
    private boolean exclusiveFullscreen;
    /** Whether the game wants fullscreen; {@link #fullscreen} is whether the window is. */
    private boolean fullscreenRequested;

    private boolean fullscreen;
    private boolean fullscreenModeDirty;

    private String errorSection = "Startup";
    private boolean minimized;
    private boolean hasFocus = true;
    private boolean shouldClose;
    private @Nullable Runnable closeCallback;
    private boolean allowCursorChanges;
    private boolean quitShortcuts;
    private CursorType currentCursor = CursorType.DEFAULT;

    public Window(
            final WindowEventHandler handler,
            final DisplayData startup,
            final @Nullable String savedFullscreenMode,
            final boolean wantExclusive,
            final String windowTitle,
            final MonitorManager monitors,
            final GpuBackend gpuBackend) {
        this(handler, startup, savedFullscreenMode, wantExclusive, windowTitle, monitors, gpuBackend, 0);
    }

    /**
     * @param sizeLimit the largest the window may be in either dimension, or 0 for no limit
     */
    public Window(
            final WindowEventHandler handler,
            final DisplayData startup,
            final @Nullable String savedFullscreenMode,
            final boolean wantExclusive,
            final String windowTitle,
            final MonitorManager monitors,
            final GpuBackend gpuBackend,
            final int sizeLimit) {
        this.eventHandler = handler;
        this.monitorManager = monitors;
        this.exclusiveFullscreen = wantExclusive;
        this.preferredFullscreenVideoMode = initialFullscreenMode(savedFullscreenMode, startup);
        this.fullscreenRequested = startup.isFullscreen();

        this.screenWidth = Math.max(startup.width(), MIN_WINDOW_WIDTH);
        this.screenHeight = Math.max(startup.height(), MIN_WINDOW_HEIGHT);
        this.restoreWidth = this.screenWidth;
        this.restoreHeight = this.screenHeight;
        WindowSpec spec = new WindowSpec(windowTitle, this.screenWidth, this.screenHeight, sizeLimit);
        this.platformWindow = ClientPlatform.get().createWindow(spec, gpuBackend::createWindow);
        ClientPlatform.windowCreated(this.platformWindow);
        MacosUtil.disableCloseWindowMenuItem();

        // Start centred on the primary display; where the platform can't say, keep where it put us.
        Monitor primary = monitors.getMonitor(ClientPlatform.get().displays().primaryId());
        this.screenX = primary == null ? this.platformWindow.x() : primary.x() + (primary.w() - this.screenWidth) / 2;
        this.screenY = primary == null ? this.platformWindow.y() : primary.y() + (primary.h() - this.screenHeight) / 2;
        this.restoreX = this.screenX;
        this.restoreY = this.screenY;

        this.applyRequestedMode();
        this.refreshFramebufferSize();
    }

    private static Optional<VideoMode> initialFullscreenMode(
            final @Nullable String fromOptions, final DisplayData startup) {
        Optional<VideoMode> saved = VideoMode.read(fromOptions);
        if (saved.isPresent()) {
            return saved;
        }
        if (startup.fullscreenWidth().isPresent() && startup.fullscreenHeight().isPresent()) {
            // Sizes from the command line: assume the everyday 24-bit, 60 Hz mode.
            return Optional.of(new VideoMode(
                    startup.fullscreenWidth().getAsInt(),
                    startup.fullscreenHeight().getAsInt(),
                    8,
                    8,
                    8,
                    60));
        }
        return Optional.empty();
    }

    /** The platform's name for crash reports, for example "SDL on Linux (wayland)". */
    public static String getPlatform() {
        return ClientPlatform.get().description();
    }

    /** The platform window behind this one, for Incari code that needs more than the game uses. */
    public PlatformWindow platformWindow() {
        return this.platformWindow;
    }

    public @Nullable VideoMode getActiveVideoMode() {
        RenderSystem.assertOnRenderThread();
        DisplayMode mode = this.platformWindow.currentDisplayMode();
        return mode == null ? null : DisplayModes.toVideoMode(mode);
    }

    public boolean shouldClose() {
        return this.shouldClose;
    }

    // --- Events from the platform, delivered by SDLEventHandler on the render thread ---

    void onCloseRequested() {
        // The system's quit shortcut (Alt+F4, Command+Q) closes the game only when the player
        // hasn't bound it to something else.
        if (this.quitShortcuts || !InputQuirks.isQuitShortcutDown()) {
            this.requestClose();
        }
    }

    void onTerminating() {
        this.requestClose();
    }

    void onDisplayAdded(final int displayId) {
        this.monitorManager.onDisplayConnected(displayId);
    }

    void onDisplayRemoved(final int displayId) {
        this.monitorManager.onDisplayDisconnected(displayId);
    }

    void onDisplayModeChanged(final int displayId) {
        this.monitorManager.onDisplayModeChanged(displayId);
        this.refreshFramebufferSize();
        this.eventHandler.framebufferSizeChanged();
    }

    void onDisplayChanged() {
        this.eventHandler.framebufferSizeChanged();
    }

    void onMoved(final int newX, final int newY) {
        this.screenX = newX;
        this.screenY = newY;
        if (!this.platformWindow.isFullscreen()) {
            this.restoreX = newX;
            this.restoreY = newY;
        }
    }

    void onResized(final int newWidth, final int newHeight) {
        this.screenWidth = newWidth;
        this.screenHeight = newHeight;
        if (!this.platformWindow.isFullscreen()) {
            this.restoreWidth = Math.max(newWidth, MIN_WINDOW_WIDTH);
            this.restoreHeight = Math.max(newHeight, MIN_WINDOW_HEIGHT);
        }
        // Keep the pointer lock centred in the new size.
        if (Minecraft.getInstance().mouseHandler.isMouseGrabbed()) {
            InputConstants.grabMouse(this, this.getScreenWidth() / 2.0, this.getScreenHeight() / 2.0);
        }
    }

    void onFramebufferResized(final int pixelWidth, final int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        int previousWidth = this.pixelWidth;
        int previousHeight = this.pixelHeight;
        this.pixelWidth = pixelWidth - this.platformWindow.unusedWidth();
        this.pixelHeight = pixelHeight;
        try {
            this.eventHandler.framebufferSizeChanged();
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Window resize");
            CrashReportCategory sizes = report.addCategory("Window Dimensions");
            sizes.setDetail("Old", previousWidth + "x" + previousHeight);
            sizes.setDetail("New", pixelWidth + "x" + pixelHeight);
            throw new ReportedException(report);
        }
    }

    void onMinimized(final boolean minimized) {
        this.minimized = minimized;
        Minecraft.getInstance().invalidateSurfaceConfiguration();
    }

    void onFocusChanged(final boolean hasFocus) {
        this.hasFocus = hasFocus;
    }

    void onCursorEnteredOrLeft() {
        this.eventHandler.cursorEntered();
    }

    void onFullscreenChanged() {
        this.syncFullscreenState();
    }

    private void syncFullscreenState() {
        boolean nowFullscreen = this.platformWindow.isFullscreen();
        if (nowFullscreen != this.fullscreen) {
            this.fullscreen = nowFullscreen;
            this.eventHandler.fullscreenStateChanged(nowFullscreen);
        }
    }

    private void requestClose() {
        this.shouldClose = true;
        if (this.closeCallback != null) {
            this.closeCallback.run();
        }
    }

    // --- Icon ---

    /** Incari's icon in each size the window can pick from (tools/icon), in the Incari pack. */
    private static final int[] ICON_SIZES = {16, 32, 48, 128, 256};

    /**
     * Shows Incari's icon, not the game's: {@code resources} and {@code iconSet} name the game's,
     * and are left unread.
     */
    public void setIcon(final PackMetadataResources resources, final IconSet iconSet) throws IOException {
        List<IconImage> sizes = new ArrayList<>(ICON_SIZES.length);
        for (int size : ICON_SIZES) {
            String path = "/incari-pack/assets/incari/textures/gui/icon_" + size + ".png";
            try (InputStream in = Window.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IOException("Incari's pack has no " + path);
                }
                try (NativeImage image = NativeImage.read(in)) {
                    sizes.add(new IconImage(image.getWidth(), image.getHeight(), rgbaBytes(image)));
                }
            }
        }
        this.platformWindow.setIcon(sizes);
    }

    /** The image's pixels as R, G, B, A bytes, row by row. */
    private static byte[] rgbaBytes(final NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgba = new byte[width * height * 4];
        int at = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                // NativeImage.getPixel is ARGB.
                int argb = image.getPixel(column, row);
                rgba[at++] = (byte) (argb >> 16);
                rgba[at++] = (byte) (argb >> 8);
                rgba[at++] = (byte) argb;
                rgba[at++] = (byte) (argb >>> 24);
            }
        }
        return rgba;
    }

    // --- Crash reporting ---

    public String getErrorSection() {
        return this.errorSection;
    }

    public void setErrorSection(final String section) {
        this.errorSection = section;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        ClientPlatform.windowClosed();
        this.platformWindow.close();
    }

    // --- Size and mode ---

    private void refreshFramebufferSize() {
        FramebufferSize size = this.queryFramebufferSize();
        this.pixelWidth = size.width() - this.platformWindow.unusedWidth();
        this.pixelHeight = size.height();
    }

    /** The whole drawable surface in pixels, which is what the graphics surface is sized to. */
    public FramebufferSize queryFramebufferSize() {
        return new FramebufferSize(
                Math.max(this.platformWindow.framebufferWidth(), 1),
                Math.max(this.platformWindow.framebufferHeight(), 1));
    }

    public void updateFullscreenIfChanged() {
        RenderSystem.assertOnRenderThread();
        if (this.fullscreenRequested != this.fullscreen) {
            this.applyRequestedMode();
            this.eventHandler.framebufferSizeChanged();
        }
    }

    public Optional<VideoMode> getPreferredFullscreenVideoMode() {
        return this.preferredFullscreenVideoMode;
    }

    public void setPreferredFullscreenVideoMode(final Optional<VideoMode> mode) {
        if (!mode.equals(this.preferredFullscreenVideoMode)) {
            this.preferredFullscreenVideoMode = mode;
            this.fullscreenModeDirty = true;
        }
    }

    public void changeFullscreenVideoMode() {
        RenderSystem.assertOnRenderThread();
        if (this.fullscreenRequested && this.fullscreenModeDirty) {
            this.fullscreenModeDirty = false;
            this.applyRequestedMode();
            this.eventHandler.framebufferSizeChanged();
        }
    }

    /** Puts the window into the mode the game asked for: windowed, borderless or exclusive. */
    private void applyRequestedMode() {
        RenderSystem.assertOnRenderThread();
        if (this.fullscreenRequested && !this.fullscreen) {
            // Remember the windowed placement to come back to.
            this.restoreX = this.screenX;
            this.restoreY = this.screenY;
            this.restoreWidth = Math.max(this.screenWidth, MIN_WINDOW_WIDTH);
            this.restoreHeight = Math.max(this.screenHeight, MIN_WINDOW_HEIGHT);
        }

        boolean applied;
        if (this.fullscreenRequested) {
            applied = this.platformWindow.setFullscreen(this.exclusiveFullscreen ? this.exclusiveTarget() : null);
        } else {
            int windowWidth = Math.max(this.restoreWidth, MIN_WINDOW_WIDTH);
            int windowHeight = Math.max(this.restoreHeight, MIN_WINDOW_HEIGHT);
            applied = this.platformWindow.setWindowed(this.restoreX, this.restoreY, windowWidth, windowHeight);
            if (applied) {
                this.screenX = this.restoreX;
                this.screenY = this.restoreY;
                this.screenWidth = windowWidth;
                this.screenHeight = windowHeight;
            }
        }

        if (!applied) {
            LOGGER.error("Couldn't {} fullscreen", this.fullscreenRequested ? "enter" : "leave");
            this.fullscreenRequested = this.fullscreen;
            this.eventHandler.fullscreenStateChanged(this.fullscreen);
            return;
        }
        if (this.fullscreenRequested) {
            this.screenX = this.platformWindow.x();
            this.screenY = this.platformWindow.y();
            this.screenWidth = this.platformWindow.width();
            this.screenHeight = this.platformWindow.height();
        }
        this.syncFullscreenState();
        this.refreshFramebufferSize();
        if (this.exclusiveFullscreen && this.fullscreen && !this.platformWindow.isExclusiveFullscreen()) {
            LOGGER.info("Exclusive fullscreen request resolved to borderless desktop");
        }
    }

    /** The display mode exclusive fullscreen asks for: the player's choice if this monitor has it. */
    private @Nullable DisplayMode exclusiveTarget() {
        Monitor monitor = this.monitorManager.findBestMonitor(this);
        if (monitor == null) {
            LOGGER.warn(
                    "Failed to find suitable monitor for exclusive fullscreen, falling back to borderless fullscreen");
            return null;
        }
        VideoMode mode = monitor.getPreferredVideoMode(this.preferredFullscreenVideoMode);
        LOGGER.info("Exclusive target {} on monitor {}", mode, monitor);
        return DisplayModes.toPlatform(mode);
    }

    public boolean isExclusiveFullscreen() {
        return this.platformWindow.isExclusiveFullscreen();
    }

    public void setExclusiveFullscreen(final boolean exclusive) {
        if (this.exclusiveFullscreen != exclusive) {
            this.exclusiveFullscreen = exclusive;
            if (this.fullscreenRequested) {
                this.applyRequestedMode();
                this.eventHandler.framebufferSizeChanged();
            }
        }
    }

    public void setWindowed(final int windowWidth, final int windowHeight) {
        this.restoreWidth = Math.max(windowWidth, MIN_WINDOW_WIDTH);
        this.restoreHeight = Math.max(windowHeight, MIN_WINDOW_HEIGHT);
        this.fullscreenRequested = false;
        this.applyRequestedMode();
    }

    /**
     * The largest GUI scale up to {@code maxScale} (0 for no limit) at which the GUI's base size
     * still fits the framebuffer; with {@code enforceUnicode}, rounded up to even so the Unicode
     * font's half-pixel glyphs land on whole pixels.
     */
    public int calculateScale(final int maxScale, final boolean enforceUnicode) {
        int fitsWidth = Math.max(1, this.pixelWidth / BASE_WIDTH);
        int fitsHeight = Math.max(1, this.pixelHeight / BASE_HEIGHT);
        int scale = Math.min(fitsWidth, fitsHeight);
        if (maxScale > 0) {
            scale = Math.min(scale, maxScale);
        }
        if (enforceUnicode && scale % 2 != 0) {
            scale++;
        }
        return scale;
    }

    public void setTitle(final String title) {
        this.platformWindow.setTitle(title);
    }

    public void setWindowMaxSize(final int maxWidth, final int maxHeight) {
        this.platformWindow.setMaximumSize(maxWidth, maxHeight);
    }

    /** The id input events carry, so handlers can tell this window's events apart. */
    public long handle() {
        return this.platformWindow.nativeHandle();
    }

    // Framebuffer, in pixels: what rendering uses.

    public int getWidth() {
        return this.pixelWidth;
    }

    public int getHeight() {
        return this.pixelHeight;
    }

    public void setWidth(final int pixels) {
        this.pixelWidth = pixels;
    }

    public void setHeight(final int pixels) {
        this.pixelHeight = pixels;
    }

    // The window on the desktop, in screen coordinates.

    public int getX() {
        return this.screenX;
    }

    public int getY() {
        return this.screenY;
    }

    public int getScreenWidth() {
        return this.screenWidth;
    }

    public int getScreenHeight() {
        return this.screenHeight;
    }

    // The GUI: pixels divided by the GUI scale.

    public int getGuiScale() {
        return this.scaleFactor;
    }

    public int getGuiScaledWidth() {
        return this.scaledWidth;
    }

    public int getGuiScaledHeight() {
        return this.scaledHeight;
    }

    public void setGuiScale(final int scale) {
        this.scaleFactor = scale;
        // Round up so the GUI always covers the whole framebuffer.
        this.scaledWidth = (this.pixelWidth + scale - 1) / scale;
        this.scaledHeight = (this.pixelHeight + scale - 1) / scale;
    }

    // State.

    public boolean isIconified() {
        return this.minimized;
    }

    public boolean isFocused() {
        return this.hasFocus;
    }

    public void setFullscreen(final boolean wanted) {
        this.fullscreenRequested = wanted;
    }

    public float getPixelDensity() {
        return this.platformWindow.pixelDensity();
    }

    public @Nullable Monitor findBestMonitor() {
        return this.monitorManager.findBestMonitor(this);
    }

    public void setWindowCloseCallback(final Runnable task) {
        this.closeCallback = task;
    }

    public void setAllowCursorChanges(final boolean allow) {
        this.allowCursorChanges = allow;
    }

    public void setQuitShortcuts(final boolean enabled) {
        this.quitShortcuts = enabled;
        this.platformWindow.setQuitShortcutEnabled(enabled);
    }

    public void selectCursor(final CursorType cursor) {
        CursorType wanted = this.allowCursorChanges ? cursor : CursorType.DEFAULT;
        if (!wanted.equals(this.currentCursor)) {
            this.currentCursor = wanted;
            wanted.select();
        }
    }

    /** Outline width for debug shapes: 2.5 at 1080p and below, scaling up with the framebuffer. */
    public float getAppropriateLineWidth() {
        return Math.max(2.5F, this.getWidth() / 1920.0F * 2.5F);
    }

    public record FramebufferSize(int width, int height) {}

    public static class WindowInitFailed extends SilentInitException {
        private static final long serialVersionUID = 1L;

        public WindowInitFailed(final String message) {
            super(message);
        }
    }
}
