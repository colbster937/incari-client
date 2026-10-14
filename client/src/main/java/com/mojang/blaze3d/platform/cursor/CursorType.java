package com.mojang.blaze3d.platform.cursor;

import incari.client.platform.ClientPlatform;
import incari.platform.api.window.PlatformWindow;
import incari.platform.api.window.SystemCursor;

/**
 * Incari's replacement for Mojang's cursor type (patches/classes.txt). A cursor is one of the
 * platform's standard shapes; the platform creates the native cursor when it's first shown.
 */
public class CursorType {

    public static final CursorType DEFAULT = new CursorType("default", SystemCursor.DEFAULT);

    private final String name;
    private final SystemCursor cursor;

    private CursorType(final String name, final SystemCursor cursor) {
        this.name = name;
        this.cursor = cursor;
    }

    public void select() {
        PlatformWindow window = ClientPlatform.window();
        if (window != null) {
            window.setCursor(this.cursor);
        }
    }

    @Override
    public String toString() {
        return this.name;
    }

    /**
     * Makes a cursor from one of the standard shapes {@code CursorTypes} names by SDL's
     * {@code SDL_SystemCursor} number; a shape the platform can't show falls back to
     * {@code fallback}.
     */
    public static CursorType createStandardCursor(final int shape, final String name, final CursorType fallback) {
        SystemCursor cursor =
                switch (shape) {
                    case 0 -> SystemCursor.ARROW;
                    case 1 -> SystemCursor.TEXT;
                    case 3 -> SystemCursor.CROSSHAIR;
                    case 7 -> SystemCursor.RESIZE_EW;
                    case 8 -> SystemCursor.RESIZE_NS;
                    case 9 -> SystemCursor.RESIZE_ALL;
                    case 10 -> SystemCursor.NOT_ALLOWED;
                    case 11 -> SystemCursor.POINTING_HAND;
                    default -> null;
                };
        return cursor == null ? fallback : new CursorType(name, cursor);
    }
}
