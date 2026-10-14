package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An EaglerXServer's pause menu (message {@link EaglerMessages#S_CUSTOMIZE_PAUSE_MENU}, sent to
 * clients that ask for {@link EaglerLogin#PAUSE_MENU}): a "server info" button, a Discord button,
 * and small images for the menu's buttons and title, each named by a key such as {@code
 * icon_discord_L} (left of the Discord button's text) or {@code icon_title_R}.
 *
 * @param serverInfo the server info button, or null for none
 * @param discord the Discord button, or null for none
 * @param icons each icon key's image
 */
public record EaglerPauseMenu(
        @Nullable Button serverInfo, @Nullable Button discord, Map<String, Image> icons) {

    /** Opens {@code url} in a new tab. */
    public static final int MODE_EXTERNAL_URL = 1;
    /** Shows {@code url} in an embedded web view; the page has none, so it opens in a new tab. */
    public static final int MODE_EMBED_URL = 2;
    /** Shows markup downloaded from the server in an embedded web view; {@code url} is null. */
    public static final int MODE_EMBED_DOWNLOAD = 3;

    /** The largest icon side EaglerXServer sends. */
    private static final int MAX_ICON_SIZE = 64;

    /**
     * A button: its text, how it acts ({@link #MODE_EXTERNAL_URL} and the other modes; always the
     * first for Discord), and the address it opens if it has one.
     */
    public record Button(String text, int mode, @Nullable String url) {}

    /** An image, {@code width × height} pixels of ARGB, row by row; fully transparent or opaque. */
    public static final class Image {
        private final int width;
        private final int height;
        private final int[] argb;

        Image(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The pixel at ({@code x}, {@code y}). */
        public int pixel(int x, int y) {
            return argb[y * width + x];
        }
    }

    /** Reads the message's body (the id already read). */
    public static EaglerPauseMenu read(ByteBuf in) {
        int flags = in.readUnsignedByte();
        int serverInfoMode = flags & 0x0F;
        int discordMode = (flags >> 4) & 0x0F;
        Button serverInfo = null;
        switch (serverInfoMode) {
            case MODE_EXTERNAL_URL -> serverInfo = new Button(Wire.readString(in), MODE_EXTERNAL_URL, shortAscii(in));
            case MODE_EMBED_URL -> {
                String text = Wire.readString(in);
                String url = shortAscii(in);
                in.skipBytes(1); // the web view's permissions
                Wire.readString(in); // the web view screen's title
                serverInfo = new Button(text, MODE_EMBED_URL, url);
            }
            case MODE_EMBED_DOWNLOAD -> {
                String text = Wire.readString(in);
                in.skipBytes(1); // permissions
                Wire.readString(in); // title
                in.skipBytes(20); // the markup's SHA-1, which asks for it
                serverInfo = new Button(text, MODE_EMBED_DOWNLOAD, null);
            }
            default -> {}
        }
        Button discord = null;
        if (discordMode == MODE_EXTERNAL_URL) {
            discord = new Button(Wire.readString(in), MODE_EXTERNAL_URL, shortAscii(in));
        }
        Map<String, Image> icons = new HashMap<>();
        int mappings = Wire.readVarInt(in);
        if (mappings > 0) {
            Map<String, Integer> indices = new HashMap<>();
            for (int i = 0; i < mappings; i++) {
                String key = Wire.readByteAscii(in);
                indices.put(key, Wire.readVarInt(in));
            }
            int count = Wire.readVarInt(in);
            List<Image> images = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                images.add(readImage(in));
            }
            indices.forEach((key, index) -> {
                if (index >= 0 && index < images.size()) {
                    icons.put(key, images.get(index));
                }
            });
        }
        return new EaglerPauseMenu(serverInfo, discord, Map.copyOf(icons));
    }

    /** An unsigned short length, then one byte a character. */
    private static String shortAscii(ByteBuf in) {
        return EaglerMessages.readShortAscii(in);
    }

    /**
     * Eagler's 16-bit image: a byte each for width and height, then a short a pixel, RGB 5:6:5 with
     * blue counted from 1, and 0 for transparent.
     */
    private static Image readImage(ByteBuf in) {
        int width = in.readUnsignedByte();
        int height = in.readUnsignedByte();
        if (width < 1 || height < 1 || width > MAX_ICON_SIZE || height > MAX_ICON_SIZE) {
            throw new DecoderException("Bad pause menu icon size " + width + "x" + height);
        }
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            int pixel = in.readUnsignedShort();
            if (pixel == 0) {
                continue;
            }
            int red = (pixel >>> 11) & 0x1F;
            int green = (pixel >>> 5) & 0x3F;
            int blue = Math.max(0, (pixel & 0x1F) - 1);
            argb[i] = 0xFF000000 | (red * 255 / 31) << 16 | (green * 255 / 63) << 8 | blue * 255 / 30;
        }
        return new Image(width, height, argb);
    }
}
