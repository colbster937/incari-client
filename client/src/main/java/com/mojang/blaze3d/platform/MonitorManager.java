package com.mojang.blaze3d.platform;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.platform.api.window.DisplayInfo;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's replacement for Mojang's monitor list (patches/classes.txt): the connected displays by
 * platform id, kept current by the platform's display events on the render thread.
 */
public class MonitorManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Int2ObjectMap<Monitor> byId = new Int2ObjectOpenHashMap<>();

    public MonitorManager() {
        for (DisplayInfo display : ClientPlatform.get().displays().all()) {
            this.update(display.id(), "found");
        }
    }

    // Display events arrive on the render thread, which also owns the map.

    public void onDisplayConnected(final int id) {
        RenderSystem.assertOnRenderThread();
        this.update(id, "connected");
    }

    public void onDisplayModeChanged(final int id) {
        RenderSystem.assertOnRenderThread();
        this.update(id, "changed mode");
    }

    public void onDisplayDisconnected(final int id) {
        RenderSystem.assertOnRenderThread();
        Monitor gone = this.byId.remove(id);
        if (gone != null) {
            LOGGER.debug("Display {} disconnected; displays now {}", gone, this.byId.values());
        }
    }

    /** Re-reads one display from the platform; one that can't be read is left out. */
    private void update(final int id, final String what) {
        Monitor fresh = Monitor.tryCreate(id);
        if (fresh == null) {
            return;
        }
        this.byId.put(id, fresh);
        LOGGER.debug("Display {} {}; displays now {}", fresh, what, this.byId.values());
    }

    public @Nullable Monitor getMonitor(final int id) {
        return this.byId.get(id);
    }

    /** The monitor the window is mostly on. */
    public @Nullable Monitor findBestMonitor(final Window window) {
        return this.byId.get(window.platformWindow().displayId());
    }
}
