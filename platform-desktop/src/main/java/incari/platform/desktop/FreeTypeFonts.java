package incari.platform.desktop;

import incari.platform.api.fonts.FontFace;
import incari.platform.api.fonts.PlatformFonts;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;

/**
 * Smooth fonts through FreeType, the library the game's own TrueType provider uses, with a library
 * instance of its own. Glyphs are rendered with the font's hinting turned to "light" (vertical
 * only), as FreeType's default does for anti-aliased text: shapes keep their widths, edges stay
 * soft.
 */
final class FreeTypeFonts implements PlatformFonts {

    /** FreeType's library isn't thread-safe; faces made from it share it. */
    private static final Object LOCK = new Object();

    private long library;

    @Override
    public @Nullable FontFace open(byte[] data, String name, float pixelsPerEm) {
        synchronized (LOCK) {
            if (library == 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pointer = stack.mallocPointer(1);
                    if (FreeType.FT_Init_FreeType(pointer) != 0) {
                        return null;
                    }
                    library = pointer.get(0);
                }
            }
            ByteBuffer memory = MemoryUtil.memAlloc(data.length);
            memory.put(data).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                if (FreeType.FT_New_Memory_Face(library, memory, 0, pointer) != 0) {
                    MemoryUtil.memFree(memory);
                    return null;
                }
                FT_Face face = FT_Face.create(pointer.get(0));
                FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE);
                int size = Math.max(1, Math.round(pixelsPerEm));
                FreeType.FT_Set_Pixel_Sizes(face, size, size);
                return new Face(face, memory);
            }
        }
    }

    private static final class Face implements FontFace {

        private @Nullable FT_Face face;
        private @Nullable ByteBuffer memory;

        Face(FT_Face face, ByteBuffer memory) {
            this.face = face;
            this.memory = memory;
        }

        @Override
        public FontFace.@Nullable Glyph glyph(int codepoint) {
            synchronized (LOCK) {
                FT_Face open = face;
                if (open == null) {
                    return null;
                }
                int index = FreeType.FT_Get_Char_Index(open, codepoint);
                if (index == 0
                        || FreeType.FT_Load_Glyph(open, index, FreeType.FT_LOAD_RENDER | FreeType.FT_LOAD_TARGET_LIGHT)
                                != 0) {
                    return null;
                }
                FT_GlyphSlot slot = open.glyph();
                if (slot == null) {
                    return null;
                }
                FT_Bitmap bitmap = slot.bitmap();
                int width = bitmap.width();
                int height = bitmap.rows();
                int pitch = bitmap.pitch();
                byte[] coverage = new byte[width * height];
                ByteBuffer buffer = width > 0 && height > 0 ? bitmap.buffer(Math.abs(pitch) * height) : null;
                if (buffer != null) {
                    for (int y = 0; y < height; y++) {
                        int row = pitch >= 0 ? y * pitch : (height - 1 - y) * -pitch;
                        for (int x = 0; x < width; x++) {
                            coverage[y * width + x] = buffer.get(row + x);
                        }
                    }
                }
                float advance = slot.advance().x() / 64.0F;
                return new FontFace.Glyph(advance, slot.bitmap_left(), slot.bitmap_top(), width, height, coverage);
            }
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (face != null) {
                    FreeType.FT_Done_Face(face);
                    face = null;
                }
                if (memory != null) {
                    MemoryUtil.memFree(memory);
                    memory = null;
                }
            }
        }
    }
}
