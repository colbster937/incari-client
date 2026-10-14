package incari.client.platform;

import com.mojang.blaze3d.platform.VideoMode;
import incari.platform.api.window.DisplayMode;

/** Converts between the game's {@link VideoMode} and the platform's {@link DisplayMode}. */
public final class DisplayModes {

    private DisplayModes() {}

    public static VideoMode toVideoMode(DisplayMode mode) {
        return new VideoMode(
                mode.width(), mode.height(), mode.redBits(), mode.greenBits(), mode.blueBits(), mode.refreshRate());
    }

    public static DisplayMode toPlatform(VideoMode mode) {
        return new DisplayMode(
                mode.getWidth(),
                mode.getHeight(),
                mode.getRedBits(),
                mode.getGreenBits(),
                mode.getBlueBits(),
                mode.getRefreshRate());
    }
}
