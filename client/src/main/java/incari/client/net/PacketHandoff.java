package incari.client.net;

import incari.client.platform.ClientPlatform;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;

/**
 * Packets handed to the game's thread without the round trip (ADR 0033). The game decodes each
 * packet on the network's thread and calls its handler there; nearly every handler's first line
 * ({@code PacketUtils.ensureRunningOnSameThread}) queues the packet for the game's thread and throws
 * a shared exception, which the connection catches; the game's thread later calls the handler
 * again, for real. In the page a throw caught on any thread but the main one costs 640 to 860 ns
 * on the desktop ({@code app-web/probes/PacketHandoffProbe}), several microseconds on the floor
 * stand-in, every packet: in combat 2.5% of the page's samples, mostly entity motion and movement.
 *
 * <p>Here, the first time a packet type's handler throws to hand it over (for a listener type), the
 * pair is noted, and from then on the connection queues such packets for the game's thread itself,
 * as the handler would, without calling it on the network's thread. That is the game's own effect
 * where the handler's first statement is the handoff, made unconditionally, to the game's packet
 * processor: true of every client handler that hands off but two ({@code PacketHandoffTest} checks
 * the game's sources), which are never noted: the custom payload's (it hands off only some
 * payloads) and the transfer's (it sets a flag first). A handler that doesn't hand off (keep-alives,
 * the login listener's) never throws, so is never noted, and runs where it did. Only the client's
 * own listeners: the integrated server's handlers hand off to other processors.
 *
 * <p>Off unless the page turns it on ({@code ?handoff=game} leaves it off); the desktop's throw costs
 * next to nothing. The network's thread, which in the page is one thread with the game's.
 */
public final class PacketHandoff {

    private static boolean enabled;

    /** By listener type, the packet types its handlers hand off. Hot path: looked up without allocating. */
    private static final IdentityHashMap<Class<?>, Set<Class<?>>> HANDED_OFF = new IdentityHashMap<>();

    private PacketHandoff() {}

    public static void setEnabled(boolean on) {
        enabled = on && ClientPlatform.oneThread();
        HANDED_OFF.clear();
    }

    /**
     * Hook ({@code Connection.channelRead0}, before the handler is called): queues {@code packet}
     * for the game's thread if its handler only hands it there, as the handler would.
     *
     * @return false if the handler is to be called, as the game calls it
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the listener is the packet's own, as the game's dispatch assumes
    public static boolean handOff(Packet<?> packet, PacketListener listener) {
        if (!enabled) {
            return false;
        }
        Set<Class<?>> packets = HANDED_OFF.get(listener.getClass());
        if (packets == null || !packets.contains(packet.getClass())) {
            return false;
        }
        // The handler's own call, which throws RejectedExecutionException once the game stops, as there.
        PacketProcessor processor = Minecraft.getInstance().packetProcessor();
        processor.scheduleIfPossible(listener, (Packet) packet);
        return true;
    }

    /**
     * Hook ({@code Connection.channelRead0}, where it catches the handler's handoff): notes that this
     * packet type's handler hands it to the game's thread.
     */
    public static void handedOff(Packet<?> packet, PacketListener listener) {
        if (enabled && listener instanceof ClientCommonPacketListenerImpl && notes(packet)) {
            HANDED_OFF
                    .computeIfAbsent(listener.getClass(), type -> Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(packet.getClass());
        }
    }

    /** Whether the handoff of this packet type may be noted: all but the two whose handlers do more first. */
    static boolean notes(Packet<?> packet) {
        return !(packet instanceof ClientboundCustomPayloadPacket) && !(packet instanceof ClientboundTransferPacket);
    }
}
