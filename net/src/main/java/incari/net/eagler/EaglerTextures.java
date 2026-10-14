package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Eagler skins and capes: this client's own, sent as profile data at login, and other players',
 * asked of the server by UUID. Converts between Eagler's compact formats and the ARGB pixels the
 * game's textures are built from (one int per pixel, {@code 0xAARRGGBB}, rows top to bottom).
 *
 * <ul>
 *   <li>A skin is 64x64 pixels at three bytes each: blue, green, then red's top seven bits with
 *       alpha as a single bit (12288 bytes), plus a model id (0 wide, 1 slim).
 *   <li>A cape is the 22x17 region the cape model uses plus one more column of 11 pixels, at three
 *       bytes each (blue, green, red; 1173 bytes), of a 64x32 cape texture.
 *   <li>Either may instead be a preset: a number naming one of Eagler's built-in textures.
 * </ul>
 */
public final class EaglerTextures {

    public static final int SKIN_WIDTH = 64;
    public static final int SKIN_HEIGHT = 64;
    public static final int CAPE_WIDTH = 64;
    public static final int CAPE_HEIGHT = 32;

    static final int SKIN_BYTES = SKIN_WIDTH * SKIN_HEIGHT * 3;
    static final int CAPE_BYTES = 23 * 17 * 3;

    /** Profile data names, as the handshake sends them. */
    public static final String SKIN_PROFILE_DATA = "skin_v2";

    public static final String CAPE_PROFILE_DATA = "cape_v1";

    private static final int PRESET = 1;
    private static final int CUSTOM = 2;

    /** Eagler's preset skins 0 and 1 are the default Steve and Alex; the rest are its own art. */
    public static final int PRESET_STEVE = 0;

    public static final int PRESET_ALEX = 1;
    /** Preset cape 0 is no cape. */
    public static final int PRESET_NO_CAPE = 0;

    /** A player's skin: a preset number, or custom pixels (64x64 ARGB, not to be changed). */
    public static final class Skin {

        private final int preset;
        private final int @Nullable [] pixels;
        private final boolean slim;

        private Skin(int preset, int @Nullable [] pixels, boolean slim) {
            this.preset = preset;
            this.pixels = pixels;
            this.slim = slim;
        }

        public static Skin preset(int preset) {
            // Eagler's odd presets are its slim ("Alex") variants.
            return new Skin(preset, null, (preset & 1) != 0);
        }

        public static Skin custom(int[] pixels, boolean slim) {
            return new Skin(-1, pixels, slim);
        }

        /** The preset's number, or -1 for a custom skin. */
        public int preset() {
            return preset;
        }

        public int @Nullable [] pixels() {
            return pixels;
        }

        public boolean slim() {
            return slim;
        }
    }

    /** A player's cape: a preset number (0: none), or custom pixels (64x32 ARGB, not to be changed). */
    public static final class Cape {

        public static final Cape NONE = new Cape(PRESET_NO_CAPE, null);

        private final int preset;
        private final int @Nullable [] pixels;

        private Cape(int preset, int @Nullable [] pixels) {
            this.preset = preset;
            this.pixels = pixels;
        }

        public static Cape preset(int preset) {
            return preset == PRESET_NO_CAPE ? NONE : new Cape(preset, null);
        }

        public static Cape custom(int[] pixels) {
            return new Cape(-1, pixels);
        }

        /** The preset's number, or -1 for a custom cape. */
        public int preset() {
            return preset;
        }

        public int @Nullable [] pixels() {
            return pixels;
        }
    }

    /** Hears textures arriving and changing. Calls come on the connection's event loop. */
    public interface Listener {

        /** The server answered a {@link #request} for {@code player}. */
        void received(UUID player, Skin skin, Cape cape);

        /** The server says {@code player}'s textures changed; ask again if they're still needed. */
        default void invalidated(UUID player) {}

        /** The server replaced this client's own skin (or cape) with {@code skin} ({@code cape}). */
        default void forced(@Nullable Skin skin, @Nullable Cape cape) {}
    }

    private final Listener listener;
    private final Map<Integer, UUID> requests = new HashMap<>();

    private @Nullable EaglerMessages messages;
    private int nextRequest = 1;

    public EaglerTextures(Listener listener) {
        this.listener = listener;
    }

    /** Starts sending requests over {@code messages}; call from {@link EaglerSession#loggedIn}. */
    public synchronized void attach(EaglerMessages messages) {
        this.messages = messages;
    }

    /** Asks the server for {@code player}'s skin and cape. Any thread; nothing before login. */
    public void request(UUID player) {
        EaglerMessages current;
        int id;
        synchronized (this) {
            current = messages;
            if (current == null) {
                return;
            }
            id = nextRequest++;
            requests.put(id, player);
        }
        current.send(EaglerMessages.C_GET_OTHER_TEXTURES, out -> {
            Wire.writeVarInt(out, id);
            out.writeLong(player.getMostSignificantBits());
            out.writeLong(player.getLeastSignificantBits());
        });
    }

    /**
     * Handles an Eagler message if it's about textures; returns whether it was. Called from {@link
     * EaglerSession#received}.
     */
    public boolean handle(int id, ByteBuf in) {
        switch (id) {
            case EaglerMessages.S_OTHER_TEXTURES -> {
                UUID player = answered(Wire.readVarInt(in));
                int skinId = Wire.readVarInt(in);
                int capeId = Wire.readVarInt(in);
                Skin skin = skinId >= 0 ? Skin.preset(skinId) : readCustomSkin(in, -skinId - 1);
                Cape cape = capeId >= 0 ? Cape.preset(capeId) : Cape.custom(readCape(in));
                if (player != null) {
                    listener.received(player, skin, cape);
                }
            }
            case EaglerMessages.S_OTHER_SKIN_PRESET,
                    EaglerMessages.S_OTHER_SKIN_CUSTOM,
                    EaglerMessages.S_OTHER_CAPE_PRESET,
                    EaglerMessages.S_OTHER_CAPE_CUSTOM -> {
                // Answers to the skin-only and cape-only requests, which this client doesn't send.
            }
            case EaglerMessages.S_FORCE_SKIN_PRESET -> listener.forced(Skin.preset(in.readInt()), null);
            case EaglerMessages.S_FORCE_SKIN_CUSTOM -> listener.forced(readCustomSkin(in, in.readUnsignedByte()), null);
            case EaglerMessages.S_FORCE_CAPE_PRESET -> listener.forced(null, Cape.preset(in.readInt()));
            case EaglerMessages.S_FORCE_CAPE_CUSTOM -> listener.forced(null, Cape.custom(readCape(in)));
            case EaglerMessages.S_INVALIDATE_PLAYER -> {
                int count = Wire.readVarInt(in);
                for (int i = 0; i < count; i++) {
                    in.readUnsignedByte(); // which of skin and cape changed: both are asked for again
                    listener.invalidated(new UUID(in.readLong(), in.readLong()));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private synchronized @Nullable UUID answered(int request) {
        return requests.remove(request);
    }

    private static Skin readCustomSkin(ByteBuf in, int rawModel) {
        byte[] data = new byte[SKIN_BYTES];
        in.readBytes(data);
        // The top bit marks skins converted from older formats; the rest is the model.
        return Skin.custom(decodeSkin(data), (rawModel & 0x7F) == 1);
    }

    private static int[] readCape(ByteBuf in) {
        byte[] data = new byte[CAPE_BYTES];
        in.readBytes(data);
        return decodeCape(data);
    }

    // --- Formats -----------------------------------------------------------------------------

    /** Eagler's 12288-byte skin as 64x64 ARGB pixels. */
    public static int[] decodeSkin(byte[] data) {
        if (data.length != SKIN_BYTES) {
            throw new IllegalArgumentException("A skin is " + SKIN_BYTES + " bytes, not " + data.length);
        }
        int[] pixels = new int[SKIN_WIDTH * SKIN_HEIGHT];
        for (int i = 0; i < pixels.length; i++) {
            int blue = data[i * 3] & 0xFF;
            int green = data[i * 3 + 1] & 0xFF;
            int redAlpha = data[i * 3 + 2] & 0xFF;
            // Seven bits of red, restored the way Eagler's own clients do, so colours match theirs.
            int red = (redAlpha & 0x7F) << 1;
            int alpha = (redAlpha & 0x80) != 0 ? 0xFF : 0;
            pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        return pixels;
    }

    /** 64x64 ARGB pixels in Eagler's 12288-byte skin format (alpha kept as one bit). */
    public static byte[] encodeSkin(int[] pixels) {
        if (pixels.length != SKIN_WIDTH * SKIN_HEIGHT) {
            throw new IllegalArgumentException("A skin is 64x64 pixels");
        }
        byte[] data = new byte[SKIN_BYTES];
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            data[i * 3] = (byte) argb;
            data[i * 3 + 1] = (byte) (argb >>> 8);
            data[i * 3 + 2] = (byte) (((argb >>> 17) & 0x7F) | ((argb >>> 24) & 0x80));
        }
        return data;
    }

    /** Eagler's 1173-byte cape as 64x32 ARGB pixels; what it doesn't carry is transparent. */
    public static int[] decodeCape(byte[] data) {
        if (data.length != CAPE_BYTES) {
            throw new IllegalArgumentException("A cape is " + CAPE_BYTES + " bytes, not " + data.length);
        }
        int[] pixels = new int[CAPE_WIDTH * CAPE_HEIGHT];
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 22; x++) {
                pixels[y * CAPE_WIDTH + x] = capePixel(data, (y * 23 + x) * 3);
            }
        }
        // The 23rd column's lower 11 rows hold the cape's column 22, rows 11 to 21.
        for (int y = 0; y < 11; y++) {
            pixels[(y + 11) * CAPE_WIDTH + 22] = capePixel(data, ((y + 6) * 23 + 22) * 3);
        }
        return pixels;
    }

    /** 64x32 ARGB cape pixels in Eagler's 1173-byte format. */
    public static byte[] encodeCape(int[] pixels) {
        if (pixels.length != CAPE_WIDTH * CAPE_HEIGHT) {
            throw new IllegalArgumentException("A cape is 64x32 pixels");
        }
        byte[] data = new byte[CAPE_BYTES];
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 22; x++) {
                putCapePixel(data, (y * 23 + x) * 3, pixels[y * CAPE_WIDTH + x]);
            }
        }
        for (int y = 0; y < 11; y++) {
            putCapePixel(data, ((y + 6) * 23 + 22) * 3, pixels[(y + 11) * CAPE_WIDTH + 22]);
        }
        return data;
    }

    private static int capePixel(byte[] data, int offset) {
        return 0xFF000000
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset] & 0xFF);
    }

    private static void putCapePixel(byte[] data, int offset, int argb) {
        data[offset] = (byte) argb;
        data[offset + 1] = (byte) (argb >>> 8);
        data[offset + 2] = (byte) (argb >>> 16);
    }

    /**
     * A 64x32 skin (from before 1.8) as a 64x64 one: the lower half gets the left arm and leg, which
     * old skins share with the right ones, mirrored face by face; the second layers stay empty.
     */
    public static int[] legacySkinToModern(int[] pixels) {
        if (pixels.length != SKIN_WIDTH * 32) {
            throw new IllegalArgumentException("A legacy skin is 64x32 pixels");
        }
        int[] modern = new int[SKIN_WIDTH * SKIN_HEIGHT];
        System.arraycopy(pixels, 0, modern, 0, pixels.length);
        mirrorLimb(modern, 0, 16, 16, 48); // right leg to left leg
        mirrorLimb(modern, 40, 16, 32, 48); // right arm to left arm
        return modern;
    }

    /**
     * Copies a 16x16 limb layout (top and bottom 4x4 faces above four 4x12 sides) from ({@code
     * fromX}, {@code fromY}) to ({@code toX}, {@code toY}) as its mirror image: every face flipped
     * left to right, and the outer and inner sides swapped.
     */
    private static void mirrorLimb(int[] skin, int fromX, int fromY, int toX, int toY) {
        copyFlipped(skin, fromX + 4, fromY, toX + 4, toY, 4, 4); // top
        copyFlipped(skin, fromX + 8, fromY, toX + 8, toY, 4, 4); // bottom
        copyFlipped(skin, fromX + 8, fromY + 4, toX, toY + 4, 4, 12); // inner side becomes outer
        copyFlipped(skin, fromX + 4, fromY + 4, toX + 4, toY + 4, 4, 12); // front
        copyFlipped(skin, fromX, fromY + 4, toX + 8, toY + 4, 4, 12); // outer side becomes inner
        copyFlipped(skin, fromX + 12, fromY + 4, toX + 12, toY + 4, 4, 12); // back
    }

    private static void copyFlipped(int[] skin, int fromX, int fromY, int toX, int toY, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                skin[(toY + y) * SKIN_WIDTH + toX + width - 1 - x] = skin[(fromY + y) * SKIN_WIDTH + fromX + x];
            }
        }
    }

    /**
     * Makes the skin's first layer opaque, as the game does for downloaded skins: the base head,
     * body and limbs have no see-through pixels, whatever the file says. The second layer (hat,
     * jacket, sleeves, trousers) keeps its transparency.
     */
    public static void makeBaseLayerOpaque(int[] skin) {
        opaque(skin, 0, 0, 32, 16); // head
        opaque(skin, 0, 16, 64, 32); // right leg, body, right arm
        opaque(skin, 16, 48, 48, 64); // left leg, left arm
    }

    private static void opaque(int[] skin, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                skin[y * SKIN_WIDTH + x] |= 0xFF000000;
            }
        }
    }

    /** The {@link #SKIN_PROFILE_DATA} entry for a custom skin. */
    public static EaglerLogin.ProfileData skinProfileData(int[] pixels, boolean slim) {
        byte[] data = new byte[2 + SKIN_BYTES];
        data[0] = CUSTOM;
        data[1] = (byte) (slim ? 1 : 0);
        System.arraycopy(encodeSkin(pixels), 0, data, 2, SKIN_BYTES);
        return new EaglerLogin.ProfileData(SKIN_PROFILE_DATA, data);
    }

    /** The {@link #SKIN_PROFILE_DATA} entry for a preset skin. */
    public static EaglerLogin.ProfileData presetSkinProfileData(int preset) {
        return new EaglerLogin.ProfileData(SKIN_PROFILE_DATA, presetData(preset));
    }

    /** The {@link #CAPE_PROFILE_DATA} entry for a custom cape. */
    public static EaglerLogin.ProfileData capeProfileData(int[] pixels) {
        byte[] data = new byte[1 + CAPE_BYTES];
        data[0] = CUSTOM;
        System.arraycopy(encodeCape(pixels), 0, data, 1, CAPE_BYTES);
        return new EaglerLogin.ProfileData(CAPE_PROFILE_DATA, data);
    }

    /**
     * A skin as Eagler's shared-world hosts take it in their login (the V3 layout): a preset, or
     * the model and the 64x64 pixels as alpha, blue, green, red bytes.
     */
    public static byte[] legacySkinPacket(Skin skin) {
        int[] pixels = skin.pixels();
        if (pixels == null) {
            return presetData(skin.preset());
        }
        byte[] data = new byte[2 + SKIN_WIDTH * SKIN_HEIGHT * 4];
        data[0] = CUSTOM;
        data[1] = (byte) (skin.slim() ? 1 : 0);
        for (int i = 0; i < SKIN_WIDTH * SKIN_HEIGHT; i++) {
            int argb = pixels[i];
            data[2 + i * 4] = (byte) (argb >>> 24);
            data[3 + i * 4] = (byte) argb;
            data[4 + i * 4] = (byte) (argb >>> 8);
            data[5 + i * 4] = (byte) (argb >>> 16);
        }
        return data;
    }

    /** A cape in the layout of {@link #CAPE_PROFILE_DATA}: a preset, or the custom cape's pixels. */
    public static byte[] capePacket(Cape cape) {
        int[] pixels = cape.pixels();
        if (pixels == null) {
            return presetData(cape.preset());
        }
        byte[] data = new byte[1 + CAPE_BYTES];
        data[0] = CUSTOM;
        System.arraycopy(encodeCape(pixels), 0, data, 1, CAPE_BYTES);
        return data;
    }

    private static byte[] presetData(int preset) {
        return new byte[] {
            PRESET, (byte) ((preset >>> 24) & 0x7F), (byte) (preset >>> 16), (byte) (preset >>> 8), (byte) preset
        };
    }
}
