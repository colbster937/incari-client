package incari.client.skins;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import incari.net.eagler.EaglerLogin;
import incari.net.eagler.EaglerTextures;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The skin and cape this player shows: one of the game's default skins, or a PNG they added
 * to their list, and optionally a cape PNG. Eagler servers get it as profile data when the player
 * logs in and pass it on to the other players; this client draws it on the player wherever no
 * server sends a skin (singleplayer, offline servers).
 *
 * <p>Kept in {@code incari/skin/} in the game directory: {@code skin.properties} for the choice,
 * {@code library/} for the added PNGs, {@code cape.png} for the cape.
 */
public final class OwnSkin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The game's default skins, each in both models; Steve's and Alex's first ones are Eagler's presets. */
    private static final String[] DEFAULT_NAMES = {"ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

    private static final String STEVE = "steve";
    private static final String ALEX = "alex";
    private static final String DEFAULT_PREFIX = "default:";
    private static final String CUSTOM_PREFIX = "custom:";

    private static final Identifier OWN_SKIN = Identifier.fromNamespaceAndPath("incari", "skins/own");
    private static final Identifier OWN_CAPE = Identifier.fromNamespaceAndPath("incari", "skins/own_cape");

    private static @Nullable OwnSkin shared;

    /**
     * One entry in the skin list.
     *
     * @param key how {@code skin.properties} names it
     * @param label what the list shows
     */
    public record Choice(String key, String label) {

        /** Whether it's a PNG the player added, rather than one of the game's. */
        public boolean custom() {
            return key.startsWith(CUSTOM_PREFIX);
        }
    }

    private final Path folder;
    private Choice choice = defaultChoices().get(0);
    private boolean slim;
    /** The skin's pixels; null for Steve and Alex, which Eagler sends as presets. */
    private int @Nullable [] skinPixels;

    private int @Nullable [] capePixels;
    /** What {@link #playerSkin} answers, until the choice changes. The game's thread only. */
    private @Nullable PlayerSkin drawn;

    private OwnSkin(Path folder) {
        this.folder = folder;
    }

    /** The player's choice, as saved in {@code gameDirectory}; Steve if nothing (usable) is saved. */
    public static OwnSkin load(Path gameDirectory) {
        OwnSkin skin = new OwnSkin(gameDirectory.resolve("incari").resolve("skin"));
        skin.read();
        return skin;
    }

    /** The instance the profile screen edits and this client draws. The game's thread only. */
    public static OwnSkin shared() {
        OwnSkin skin = shared;
        if (skin == null) {
            skin = load(Minecraft.getInstance().gameDirectory.toPath());
            shared = skin;
        }
        return skin;
    }

    /**
     * Who wears this skin where no server sends one: the player, while they play offline under
     * {@code id}; null for everyone else.
     */
    public static @Nullable PlayerSkin forSelf(java.util.UUID id) {
        net.minecraft.client.User user = Minecraft.getInstance().getUser();
        return id.equals(user.getProfileId()) && "0".equals(user.getAccessToken()) ? shared().playerSkin() : null;
    }

    /** The game's default skins, in the order the list shows them. */
    public static List<Choice> defaultChoices() {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(STEVE, "Steve"));
        choices.add(new Choice(ALEX, "Alex"));
        for (String name : DEFAULT_NAMES) {
            choices.add(new Choice(DEFAULT_PREFIX + name + ":wide", title(name)));
            choices.add(new Choice(DEFAULT_PREFIX + name + ":slim", title(name) + " (Slim)"));
        }
        choices.add(new Choice(DEFAULT_PREFIX + STEVE + ":slim", "Steve (Slim)"));
        choices.add(new Choice(DEFAULT_PREFIX + ALEX + ":wide", "Alex (Wide)"));
        return choices;
    }

    /** Every skin the list offers: the game's, then the player's own in the order they're named. */
    public List<Choice> choices() {
        List<Choice> choices = defaultChoices();
        choices.addAll(library());
        return choices;
    }

    public Choice choice() {
        return choice;
    }

    /** Whether the skin uses the slim (Alex) arms. */
    public boolean slim() {
        return slim;
    }

    public boolean hasCape() {
        return capePixels != null;
    }

    /**
     * Wears {@code next} from now on.
     *
     * @throws IOException if its PNG can't be read (an added file that's gone, say)
     */
    public void choose(Choice next) throws IOException {
        Loaded loaded = loadChoice(next.key());
        choice = next;
        slim = loaded.slim;
        skinPixels = loaded.pixels;
        drawn = null;
        save();
    }

    /**
     * Adds the PNG at {@code file} to the list and wears it: 64x64, or 64x32 from before 1.8
     * (converted). Its arms are slim if the skin leaves the wide arms' extra pixels empty.
     *
     * @throws IOException if it can't be read or isn't a skin
     */
    public Choice addSkinFile(Path file) throws IOException {
        readSkin(Files.readAllBytes(file));
        Path library = folder.resolve("library");
        Files.createDirectories(library);
        String base = fileBase(file.getFileName().toString());
        String name = base + ".png";
        for (int i = 2; Files.exists(library.resolve(name)); i++) {
            name = base + "-" + i + ".png";
        }
        Files.copy(file, library.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Choice added = custom(name);
        choose(added);
        return added;
    }

    /** Removes every skin the player added; if they wore one, Steve instead. */
    public void clearLibrary() {
        for (Choice added : library()) {
            try {
                Files.deleteIfExists(
                        folder.resolve("library").resolve(added.key().substring(CUSTOM_PREFIX.length())));
            } catch (IOException e) {
                LOGGER.warn("Couldn't remove the skin {}", added.label(), e);
            }
        }
        if (choice.custom()) {
            choice = defaultChoices().get(0);
            slim = false;
            skinPixels = null;
            drawn = null;
            save();
        }
    }

    /**
     * Makes the PNG at {@code file} the cape: a 64x32 cape texture.
     *
     * @throws IOException if it can't be read or isn't a cape
     */
    public void chooseCapeFile(Path file) throws IOException {
        int[] pixels = readCape(Files.readAllBytes(file));
        Files.createDirectories(folder);
        Files.copy(file, folder.resolve("cape.png"), StandardCopyOption.REPLACE_EXISTING);
        capePixels = pixels;
        drawn = null;
    }

    public void removeCape() {
        capePixels = null;
        drawn = null;
        try {
            Files.deleteIfExists(folder.resolve("cape.png"));
        } catch (IOException e) {
            LOGGER.warn("Couldn't remove the cape file", e);
        }
    }

    /** The skin as Eagler describes it. */
    public EaglerTextures.Skin skin() {
        int[] pixels = skinPixels;
        if (pixels != null) {
            return EaglerTextures.Skin.custom(pixels, slim);
        }
        return EaglerTextures.Skin.preset(
                choice.key().equals(ALEX) ? EaglerTextures.PRESET_ALEX : EaglerTextures.PRESET_STEVE);
    }

    public EaglerTextures.Cape cape() {
        int[] pixels = capePixels;
        return pixels != null ? EaglerTextures.Cape.custom(pixels) : EaglerTextures.Cape.NONE;
    }

    /** What the login sends: the skin, and the cape if there is one. */
    public List<EaglerLogin.ProfileData> profileData() {
        List<EaglerLogin.ProfileData> data = new ArrayList<>();
        EaglerTextures.Skin skin = skin();
        int[] pixels = skin.pixels();
        data.add(
                pixels != null
                        ? EaglerTextures.skinProfileData(pixels, skin.slim())
                        : EaglerTextures.presetSkinProfileData(skin.preset()));
        int[] cape = capePixels;
        if (cape != null) {
            data.add(EaglerTextures.capeProfileData(cape));
        }
        return data;
    }

    /**
     * The skin as the game draws it: a default skin's own texture, or the added PNG's and the cape's
     * pixels as textures of their own. The game's thread only (it registers textures); called each
     * frame, so it answers the same object until something changes.
     */
    public PlayerSkin playerSkin() {
        PlayerSkin skin = drawn;
        if (skin != null) {
            return skin;
        }
        ClientAsset.Texture body;
        int[] pixels = skinPixels;
        if (choice.custom() && pixels != null) {
            register(OWN_SKIN, EaglerTextures.SKIN_WIDTH, EaglerTextures.SKIN_HEIGHT, pixels);
            body = new ClientAsset.ResourceTexture(OWN_SKIN, OWN_SKIN);
        } else {
            body = new ClientAsset.ResourceTexture(defaultTexture(defaultName(choice.key()), slim));
        }
        ClientAsset.Texture cape = null;
        int[] capeTexture = capePixels;
        if (capeTexture != null) {
            register(OWN_CAPE, EaglerTextures.CAPE_WIDTH, EaglerTextures.CAPE_HEIGHT, capeTexture);
            cape = new ClientAsset.ResourceTexture(OWN_CAPE, OWN_CAPE);
        }
        skin = new PlayerSkin(body, cape, cape, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
        drawn = skin;
        return skin;
    }

    private static void register(Identifier id, int width, int height, int[] pixels) {
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, pixels[y * width + x]);
            }
        }
        // Registering again under the same id closes the texture it replaces.
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
    }

    /** The skins the player added, by file name. */
    private List<Choice> library() {
        Path library = folder.resolve("library");
        if (!Files.isDirectory(library)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(library)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(OwnSkin::custom)
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Couldn't list the added skins", e);
            return List.of();
        }
    }

    private static Choice custom(String fileName) {
        return new Choice(CUSTOM_PREFIX + fileName, fileBase(fileName));
    }

    /** A skin's pixels (null for the presets) and model. */
    private static final class Loaded {
        final int @Nullable [] pixels;
        final boolean slim;

        Loaded(int @Nullable [] pixels, boolean slim) {
            this.pixels = pixels;
            this.slim = slim;
        }
    }

    private Loaded loadChoice(String key) throws IOException {
        if (key.equals(STEVE)) {
            return new Loaded(null, false);
        }
        if (key.equals(ALEX)) {
            return new Loaded(null, true);
        }
        if (key.startsWith(CUSTOM_PREFIX)) {
            byte[] png = Files.readAllBytes(folder.resolve("library").resolve(key.substring(CUSTOM_PREFIX.length())));
            int[] pixels = readPixels(png, EaglerTextures.SKIN_WIDTH, EaglerTextures.SKIN_HEIGHT, 32);
            boolean slimArms =
                    pixels.length == EaglerTextures.SKIN_WIDTH * EaglerTextures.SKIN_HEIGHT && looksSlim(pixels);
            return new Loaded(finishSkin(pixels), slimArms);
        }
        boolean slimArms = key.endsWith(":slim");
        Identifier texture = defaultTexture(defaultName(key), slimArms).withPath(path -> "textures/" + path + ".png");
        Optional<Resource> resource =
                Minecraft.getInstance().getResourceManager().getResource(texture);
        if (resource.isEmpty()) {
            throw new IOException("The game has no " + texture);
        }
        try (InputStream in = resource.get().open()) {
            return new Loaded(readSkin(in.readAllBytes()), slimArms);
        }
    }

    /** The default skin a key names: {@code steve}, {@code alex} or {@code default:<name>:<model>}. */
    private static String defaultName(String key) {
        if (key.startsWith(DEFAULT_PREFIX)) {
            String rest = key.substring(DEFAULT_PREFIX.length());
            int colon = rest.indexOf(':');
            return colon < 0 ? rest : rest.substring(0, colon);
        }
        return key.equals(ALEX) ? ALEX : STEVE;
    }

    private static Identifier defaultTexture(String name, boolean slim) {
        return Identifier.withDefaultNamespace("entity/player/" + (slim ? "slim/" : "wide/") + name);
    }

    private void read() {
        Properties properties = new Properties();
        Path file = folder.resolve("skin.properties");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                LOGGER.warn("Couldn't read {}", file, e);
            }
        }
        String key = properties.getProperty("skin", STEVE);
        Choice saved = null;
        for (Choice candidate : choices()) {
            if (candidate.key().equals(key)) {
                saved = candidate;
            }
        }
        if (saved != null) {
            try {
                Loaded loaded = loadChoice(saved.key());
                choice = saved;
                slim = loaded.slim;
                skinPixels = loaded.pixels;
            } catch (IOException e) {
                LOGGER.warn("Couldn't load the skin {}; wearing Steve", saved.label(), e);
            }
        }
        Path cape = folder.resolve("cape.png");
        if (Files.isRegularFile(cape)) {
            try {
                capePixels = readCape(Files.readAllBytes(cape));
            } catch (IOException e) {
                LOGGER.warn("Couldn't use {}", cape, e);
            }
        }
    }

    private void save() {
        // Written by hand: the browser build's Properties can read but not store.
        String text = "# Incari: the skin this player wears\nskin=" + choice.key() + "\n";
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("skin.properties"), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save the skin choice", e);
        }
    }

    /**
     * Whether a 64x64 skin is drawn for the slim arms: those leave the last two columns of the right
     * arm's back empty, where a wide skin has the arm.
     */
    static boolean looksSlim(int[] pixels) {
        for (int y = 20; y < 32; y++) {
            for (int x = 54; x < 56; x++) {
                if ((pixels[y * EaglerTextures.SKIN_WIDTH + x] >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A file name without its {@code .png}, kept to what a file and the list can show. */
    static String fileBase(String fileName) {
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        base = base.replaceAll("[^A-Za-z0-9 _().-]", "_").strip();
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }
        return base.isEmpty() ? "skin" : base;
    }

    private static String title(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static int[] readSkin(byte[] png) throws IOException {
        return finishSkin(readPixels(png, EaglerTextures.SKIN_WIDTH, EaglerTextures.SKIN_HEIGHT, 32));
    }

    /** A skin as it's sent and drawn: 64x64, its base layer opaque. */
    private static int[] finishSkin(int[] pixels) {
        if (pixels.length == EaglerTextures.SKIN_WIDTH * 32) {
            pixels = EaglerTextures.legacySkinToModern(pixels);
        }
        EaglerTextures.makeBaseLayerOpaque(pixels);
        return pixels;
    }

    static int[] readCape(byte[] png) throws IOException {
        return readPixels(png, EaglerTextures.CAPE_WIDTH, EaglerTextures.CAPE_HEIGHT, EaglerTextures.CAPE_HEIGHT);
    }

    /** A PNG's pixels (ARGB), if it's {@code width} wide and {@code height} or {@code orHeight} tall. */
    private static int[] readPixels(byte[] png, int width, int height, int orHeight) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(png);
                NativeImage image = NativeImage.read(in)) {
            if (image.getWidth() != width || (image.getHeight() != height && image.getHeight() != orHeight)) {
                throw new IOException("The image is " + image.getWidth() + "x" + image.getHeight() + "; it must be "
                        + width + "x" + height + (orHeight != height ? " or " + width + "x" + orHeight : ""));
            }
            int[] pixels = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    pixels[y * image.getWidth() + x] = image.getPixel(x, y);
                }
            }
            return pixels;
        }
    }
}
