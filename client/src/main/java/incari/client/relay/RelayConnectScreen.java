package incari.client.relay;

import com.mojang.logging.LogUtils;
import incari.client.net.ServerConnections;
import incari.client.platform.ClientPlatform;
import incari.net.relay.RelayJoin;
import incari.net.relay.RelayWorldAddress;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Joins a shared world (ADR 0019): {@link RelayJoin} pairs the client with the world's host
 * through the relay, then the game logs in over the data channel as it would to a server. Shows
 * where it is meanwhile, and the reason if it fails.
 */
public final class RelayConnectScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Screen parent;
    private final String code;
    private final RelayJoin join;
    private Component status = Component.literal("Connecting...");
    private volatile @Nullable Connection connection;
    private volatile boolean cancelled;

    public RelayConnectScreen(Screen parent, String relay, String code) {
        super(Component.literal("Joining a shared world"));
        this.parent = parent;
        this.code = code;
        ClientPlatformHolder platform = new ClientPlatformHolder();
        this.join = new RelayJoin(
                platform.network,
                platform.rtc,
                relay,
                code,
                new RelayJoin.Listener() {
                    @Override
                    public void progress(String text) {
                        status = Component.literal(text);
                    }

                    @Override
                    public void joined(RelayWorldAddress address) {
                        int hostProtocol = address.incariProtocol();
                        int ours = net.minecraft.SharedConstants.getCurrentVersion()
                                .protocolVersion();
                        if (hostProtocol >= 0 && hostProtocol != ours) {
                            // An Incari host of another version (ADR 0062): Via is for 1.8 hosts only.
                            address.open(new incari.platform.api.net.NetConnection.Listener() {
                                        @Override
                                        public void connected() {}

                                        @Override
                                        public void received(java.nio.ByteBuffer data) {}

                                        @Override
                                        public void closed(@org.jspecify.annotations.Nullable Throwable cause) {}
                                    })
                                    .close();
                            failed("The host is on a different Incari Client version. You both need the same one.");
                            return;
                        }
                        login(address);
                    }

                    @Override
                    public void failed(String reason) {
                        if (!cancelled) {
                            Minecraft.getInstance()
                                    .gui
                                    .setScreen(new DisconnectedScreen(
                                            parent,
                                            Component.literal("Couldn't join " + code),
                                            Component.literal(reason)));
                        }
                    }
                },
                Util::getMillis);
        this.join.start();
    }

    /** The platform's network and WebRTC, looked up once. */
    private static final class ClientPlatformHolder {
        final incari.platform.api.net.PlatformNetwork network =
                ClientPlatform.get().network();
        final incari.platform.api.rtc.PlatformRtc rtc = ClientPlatform.get().rtc();
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
                    cancelled = true;
                    join.cancel();
                    Connection open = connection;
                    if (open != null) {
                        open.disconnect(Component.translatable("connect.aborted"));
                    }
                    Minecraft.getInstance().gui.setScreen(parent);
                })
                .bounds(this.width / 2 - 100, this.height / 4 + 132, 200, 20)
                .build());
    }

    @Override
    public void tick() {
        join.tick();
        Connection open = connection;
        if (open != null) {
            if (open.isConnected()) {
                open.tick();
            } else {
                open.handleDisconnection();
            }
        }
    }

    /** The data channel is open: log in over it, as the connect screen does over a socket. */
    private void login(RelayWorldAddress address) {
        status = Component.literal("Logging in");
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = new ServerData("Shared world " + code, "relay:" + code, ServerData.Type.OTHER);
        Screen screen = this;
        // Via may load its data first: off the render thread, as the connect screen's connector.
        Thread thread = new Thread("Shared World Connector") {
            @Override
            public void run() {
                try {
                    Connection pending = new Connection(PacketFlow.CLIENTBOUND);
                    ChannelFuture future =
                            ServerConnections.connectRelay(address, EventLoopGroupHolder.remote(false), pending);
                    future.syncUninterruptibly();
                    if (cancelled) {
                        pending.disconnect(Component.translatable("connect.aborted"));
                        return;
                    }
                    connection = pending;
                    pending.initiateServerboundPlayConnection(
                            "shared-world",
                            0,
                            LoginProtocols.SERVERBOUND,
                            LoginProtocols.CLIENTBOUND,
                            new ClientHandshakePacketListenerImpl(
                                    pending,
                                    minecraft,
                                    server,
                                    parent,
                                    false,
                                    null,
                                    text -> status = text,
                                    new LevelLoadTracker(),
                                    null),
                            false);
                    pending.send(new ServerboundHelloPacket(
                            minecraft.getUser().getName(), minecraft.getUser().getProfileId()));
                } catch (RuntimeException e) {
                    LOGGER.error("Couldn't log in to shared world {}", code, e);
                    minecraft.execute(() -> {
                        if (screen.equals(minecraft.gui.screen())) {
                            minecraft.gui.setScreen(new DisconnectedScreen(
                                    parent,
                                    Component.literal("Couldn't join " + code),
                                    Component.literal(String.valueOf(e.getMessage()))));
                        }
                    });
                }
            }
        };
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFFFF);
        graphics.centeredText(this.font, status, this.width / 2, this.height / 2 - 50, 0xFFA0A0A0);
    }
}
