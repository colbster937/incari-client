package incari.client.skins;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import incari.net.eagler.EaglerMessages;
import incari.net.eagler.EaglerSession;
import incari.net.eagler.EaglerTextures;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Players' skins on an Eagler server (ADR 0016). Eagler servers don't put skins in game
 * profiles, so the vanilla lookup finds none; instead each player's skin and cape are asked of the
 * server by UUID the first time the game draws them, and turned into textures when they arrive.
 * This player's own skin comes from {@link OwnSkin}, which the login sent to the server.
 *
 * <p>One instance per Eagler connection; {@link #lookup} consults the current one.
 */
public final class EaglerSkins implements EaglerSession, EaglerTextures.Listener {

    /** Eagler's preset skins past Steve and Alex are its own art; they show as the game's defaults. */
    private static final String[] DEFAULT_NAMES = {
        "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"
    };

    private static volatile @Nullable EaglerSkins current;

    private final OwnSkin own;
    private final EaglerTextures textures = new EaglerTextures(this);
    /** Skins that arrived; written on the game's thread, read while drawing (the same thread). */
    private final Map<UUID, PlayerSkin> skins = new ConcurrentHashMap<>();
    /** Players asked for and not answered or invalidated since. */
    private final Set<UUID> requested = ConcurrentHashMap.newKeySet();
    /** Textures this session registered, released when it ends. The game's thread only. */
    private final List<Identifier> registered = new ArrayList<>();

    private volatile @Nullable UUID self;
    private volatile boolean closed;

    private EaglerSkins(OwnSkin own) {
        this.own = own;
    }

    /** A session for a new Eagler connection, which becomes the current one. */
    public static EaglerSkins start(OwnSkin own) {
        EaglerSkins session = new EaglerSkins(own);
        current = session;
        return session;
    }

    /**
     * {@code PlayerInfo}'s skin lookup: on an Eagler server, the player's Eagler skin once it has
     * arrived; elsewhere, the skin this player chose ({@link OwnSkin#forSelf}) on themselves, as
     * Eagler clients draw it; the vanilla lookup's answer for everyone else, and until then.
     */
    public static Supplier<PlayerSkin> lookup(GameProfile profile, Supplier<PlayerSkin> vanilla) {
        UUID id = profile.id();
        return () -> {
            EaglerSkins session = current;
            if (session != null) {
                return session.skinFor(id, vanilla);
            }
            PlayerSkin own = OwnSkin.forSelf(id);
            return own != null ? own : vanilla.get();
        };
    }

    private PlayerSkin skinFor(UUID id, Supplier<PlayerSkin> vanilla) {
        PlayerSkin skin = skins.get(id);
        if (skin != null) {
            return skin;
        }
        if (!id.equals(self) && requested.add(id)) {
            textures.request(id);
        }
        return vanilla.get();
    }

    // --- EaglerSession (the connection's event loop) ----------------------------------------

    @Override
    public void loggedIn(String name, UUID uuid, EaglerMessages messages) {
        self = uuid;
        textures.attach(messages);
        EaglerTextures.Skin skin = own.skin();
        EaglerTextures.Cape cape = own.cape();
        Minecraft.getInstance().execute(() -> install(uuid, skin, cape));
    }

    @Override
    public void received(int id, ByteBuf body) {
        textures.handle(id, body);
    }

    @Override
    public void closed() {
        closed = true;
        if (current == this) {
            current = null;
        }
        Minecraft.getInstance().execute(this::releaseAll);
    }

    // --- EaglerTextures.Listener ---------------------------------------------------------------

    @Override
    public void received(UUID player, EaglerTextures.Skin skin, EaglerTextures.Cape cape) {
        Minecraft.getInstance().execute(() -> install(player, skin, cape));
    }

    @Override
    public void invalidated(UUID player) {
        requested.remove(player);
        Minecraft.getInstance().execute(() -> {
            skins.remove(player);
            release(skinId(player));
            release(capeId(player));
        });
    }

    @Override
    public void forced(EaglerTextures.@Nullable Skin skin, EaglerTextures.@Nullable Cape cape) {
        UUID me = self;
        if (me == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            PlayerSkin old = skins.get(me);
            EaglerTextures.Skin newSkin = skin != null ? skin : own.skin();
            EaglerTextures.Cape newCape =
                    cape != null ? cape : (old != null && old.cape() == null ? EaglerTextures.Cape.NONE : own.cape());
            install(me, newSkin, newCape);
        });
    }

    // --- Textures (the game's thread) ---------------------------------------------------------

    private void install(UUID player, EaglerTextures.Skin skin, EaglerTextures.Cape cape) {
        if (closed) {
            return;
        }
        ClientAsset.Texture body;
        int[] skinPixels = skin.pixels();
        if (skinPixels != null) {
            Identifier id = skinId(player);
            register(id, EaglerTextures.SKIN_WIDTH, EaglerTextures.SKIN_HEIGHT, skinPixels);
            body = new ClientAsset.ResourceTexture(id, id);
        } else {
            body = presetBody(skin.preset());
        }
        ClientAsset.Texture capeTexture = null;
        int[] capePixels = cape.pixels();
        if (capePixels != null) {
            Identifier id = capeId(player);
            register(id, EaglerTextures.CAPE_WIDTH, EaglerTextures.CAPE_HEIGHT, capePixels);
            capeTexture = new ClientAsset.ResourceTexture(id, id);
        }
        // Eagler's preset capes are Mojang's; they aren't drawn.
        PlayerModelType model = skin.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        skins.put(player, new PlayerSkin(body, capeTexture, capeTexture, model, true));
    }

    /** The game's own default skin standing in for an Eagler preset. */
    private static ClientAsset.Texture presetBody(int preset) {
        boolean slim = (preset & 1) != 0;
        String name = preset == EaglerTextures.PRESET_STEVE
                ? "steve"
                : preset == EaglerTextures.PRESET_ALEX ? "alex" : DEFAULT_NAMES[(preset >>> 1) % DEFAULT_NAMES.length];
        return new ClientAsset.ResourceTexture(
                Identifier.withDefaultNamespace("entity/player/" + (slim ? "slim/" : "wide/") + name));
    }

    private void register(Identifier id, int width, int height, int[] pixels) {
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, pixels[y * width + x]);
            }
        }
        // Registering again under the same id closes the texture it replaces.
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
        if (!registered.contains(id)) {
            registered.add(id);
        }
    }

    private void release(Identifier id) {
        if (registered.remove(id)) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private void releaseAll() {
        for (Identifier id : registered) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        registered.clear();
        skins.clear();
    }

    private static Identifier skinId(UUID player) {
        return Identifier.fromNamespaceAndPath("incari", "eagler/skin/" + player);
    }

    private static Identifier capeId(UUID player) {
        return Identifier.fromNamespaceAndPath("incari", "eagler/cape/" + player);
    }
}
