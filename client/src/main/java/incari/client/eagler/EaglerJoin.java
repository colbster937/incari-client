package incari.client.eagler;

import com.mojang.logging.LogUtils;
import incari.client.skins.EaglerSkins;
import incari.client.skins.OwnSkin;
import incari.net.eagler.EaglerMessages;
import incari.net.eagler.EaglerPauseMenu;
import incari.net.eagler.EaglerSession;
import io.netty.buffer.ByteBuf;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Everything an Eagler connection the player joins does beside the game's packets:
 * skins, voice, the server's cookie, its requests for a password, its redirects, and its pause menu. The
 * connection's event loop calls in; each part takes what's its own.
 */
public final class EaglerJoin implements EaglerSession {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String server;
    private final Path gameDirectory;
    private final EaglerSkins skins;
    private final @Nullable EaglerVoice voice;
    private int capabilities;

    private EaglerJoin(String server, Path gameDirectory, EaglerSkins skins, @Nullable EaglerVoice voice) {
        this.server = server;
        this.gameDirectory = gameDirectory;
        this.skins = skins;
        this.voice = voice;
    }

    /** The session for a join of {@code server} (its WebSocket URL). */
    public static EaglerJoin start(String server) {
        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
        EaglerSkins skins = EaglerSkins.start(OwnSkin.load(gameDirectory));
        return new EaglerJoin(server, gameDirectory, skins, EaglerVoice.start());
    }

    @Override
    public void capabilities(int accepted) {
        capabilities = accepted;
    }

    @Override
    public void loggedIn(String name, UUID uuid, EaglerMessages messages) {
        skins.loggedIn(name, uuid, messages);
        if (voice != null) {
            voice.loggedIn(uuid, messages, (capabilities & incari.net.eagler.EaglerLogin.VOICE) != 0);
        }
    }

    @Override
    public void received(int id, ByteBuf body) {
        switch (id) {
            case EaglerMessages.S_SET_COOKIE -> setCookie(body);
            case EaglerMessages.S_REDIRECT -> ServerRedirects.redirect(EaglerMessages.readShortAscii(body));
            case EaglerMessages.S_CUSTOMIZE_PAUSE_MENU -> ServerPauseMenu.received(EaglerPauseMenu.read(body));
            case EaglerMessages.S_VOICE_ALLOWED,
                    EaglerMessages.S_VOICE_CONNECT,
                    EaglerMessages.S_VOICE_CONNECT_ANNOUNCE,
                    EaglerMessages.S_VOICE_DISCONNECT_PEER,
                    EaglerMessages.S_VOICE_ICE,
                    EaglerMessages.S_VOICE_DESCRIPTION,
                    EaglerMessages.S_VOICE_GLOBAL -> {
                if (voice != null) {
                    voice.received(id, body);
                }
            }
            default -> skins.received(id, body);
        }
    }

    @Override
    public void authenticationRequired(int method, String message) {
        EaglerAuth.required(server, method, message);
    }

    @Override
    public void redirected(String address) {
        ServerRedirects.pending(address);
    }

    @Override
    public void closed() {
        skins.closed();
        ServerPauseMenu.closed();
        if (voice != null) {
            voice.closed();
        }
    }

    private void setCookie(ByteBuf body) {
        int flags = body.readUnsignedByte();
        long seconds = EaglerMessages.readVarLong(body);
        byte[] data = new byte[body.readUnsignedByte()];
        body.readBytes(data);
        boolean onDisk = (flags & 2) != 0;
        LOGGER.info("{} set a {}-byte cookie for {} s{}", server, data.length, seconds, onDisk ? ", kept on disk" : "");
        EaglerCookies.get(gameDirectory).set(server, data, seconds, onDisk);
    }
}
