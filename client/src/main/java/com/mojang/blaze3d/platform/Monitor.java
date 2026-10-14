package com.mojang.blaze3d.platform;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.client.platform.DisplayModes;
import incari.platform.api.window.DisplayInfo;
import incari.platform.api.window.DisplayMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's replacement for Mojang's monitor record (patches/classes.txt): one display and the
 * fullscreen modes the game offers on it, built from what the platform reports.
 */
public record Monitor(
        String name, int id, List<VideoMode> videoModes, VideoMode currentMode, int x, int y, int w, int h) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The display with this id, or null (and a warning) if the platform can't describe it. */
    public static @Nullable Monitor tryCreate(final int id) {
        DisplayInfo display = ClientPlatform.get().displays().get(id);
        if (display == null) {
            LOGGER.warn("Failed to query monitor {}", id);
            return null;
        }
        // The game's fullscreen resolutions need true colour; anything less isn't offered.
        List<VideoMode> modes = display.fullscreenModes().stream()
                .filter(mode -> mode.redBits() >= 8 && mode.greenBits() >= 8 && mode.blueBits() >= 8)
                .map(DisplayModes::toVideoMode)
                .toList();
        DisplayMode desktop = display.desktopMode();
        return new Monitor(
                display.name() + "[0x" + Integer.toHexString(id).toUpperCase(Locale.ROOT) + "]",
                id,
                modes,
                DisplayModes.toVideoMode(desktop),
                display.x(),
                display.y(),
                display.width(),
                display.height());
    }

    /** The player's chosen mode if this monitor has it, otherwise the desktop's. */
    public VideoMode getPreferredVideoMode(final Optional<VideoMode> chosen) {
        return chosen.filter(this.videoModes::contains).orElse(this.currentMode);
    }

    public int indexOfMode(final VideoMode mode) {
        return this.videoModes.indexOf(mode);
    }

    public VideoMode mode(final int index) {
        return this.videoModes.get(index);
    }

    public int modeCount() {
        return this.videoModes.size();
    }

    @Override
    public String toString() {
        return this.name + "(" + this.currentMode + " at (" + this.x + "," + this.y + "))";
    }
}
