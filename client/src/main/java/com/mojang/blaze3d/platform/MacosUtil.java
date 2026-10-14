package com.mojang.blaze3d.platform;

import incari.client.platform.ClientPlatform;
import incari.platform.api.window.PlatformWindow;
import incari.platform.api.window.SystemBehavior;
import net.minecraft.util.Util;

/**
 * Incari's replacement for Mojang's macOS helpers (patches/classes.txt). The two options the
 * player controls go to the platform window; elsewhere they do nothing.
 */
public final class MacosUtil {

    public static final boolean IS_MACOS = Util.getPlatform() == Util.OS.OSX;

    private MacosUtil() {}

    /**
     * Mojang hides the "Close Window" menu item, which would close the game without saving.
     * Incari's desktop runtime doesn't drive the macOS menu (it would need the Objective-C bridge
     * the browser build leaves out), so the item stays; Command+W then asks the game to close,
     * which saves as usual.
     */
    public static void disableCloseWindowMenuItem() {}

    public static void setFullscreenMenuVisibility(final boolean visible) {
        setBehavior(SystemBehavior.MENU_BAR_IN_FULLSCREEN, visible);
    }

    public static void setCtrlClickEmulatesRightClick(final boolean enabled) {
        setBehavior(SystemBehavior.CTRL_CLICK_IS_RIGHT_CLICK, enabled);
    }

    private static void setBehavior(final SystemBehavior behavior, final boolean enabled) {
        PlatformWindow window = ClientPlatform.window();
        if (IS_MACOS && window != null) {
            window.setSystemBehavior(behavior, enabled);
        }
    }
}
