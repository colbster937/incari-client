package incari.platform.api.fonts;

import org.jspecify.annotations.Nullable;

/**
 * Draws a font's glyphs from its TrueType or OpenType file, at a size in pixels. The game's own
 * TrueType provider needs FreeType, which the browser doesn't have; the browser draws text natively,
 * so each platform rasterizes with what it has, and the client's fonts look the same on both.
 */
public interface PlatformFonts {

    /** No font drawing: callers fall back to the game's bitmap fonts. */
    PlatformFonts UNSUPPORTED = (data, name, pixelsPerEm) -> null;

    /**
     * Opens the font in {@code data} for glyphs {@code pixelsPerEm} pixels to the em, or returns
     * null if this platform can't. May suspend while the font loads (the browser): call it from a
     * background thread, never the game's main thread.
     *
     * @param name a name for the font, the same for the same file (the browser registers it by name)
     */
    @Nullable
    FontFace open(byte[] data, String name, float pixelsPerEm);
}
