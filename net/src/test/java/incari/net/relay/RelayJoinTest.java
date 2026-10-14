package incari.net.relay;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import incari.platform.api.rtc.IceServer;
import incari.platform.api.rtc.Microphone;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.rtc.RemoteVoice;
import incari.platform.api.rtc.RtcPeer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A join against a scripted relay and a fake WebRTC peer. */
class RelayJoinTest {

    private long now = 1000;
    private final List<ByteBuffer> sentToRelay = new ArrayList<>();
    private NetConnection.@Nullable Listener relay;
    private boolean relayClosed;
    private final FakePeer peer = new FakePeer();
    private @Nullable RelayWorldAddress joined;
    private @Nullable String failed;

    private final PlatformNetwork network = new PlatformNetwork() {
        @Override
        public NetConnection connect(String host, int port, NetConnection.Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
            relay = listener;
            return new FakeConnection(sentToRelay, () -> relayClosed = true);
        }
    };

    private final PlatformRtc rtc = new PlatformRtc() {
        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public Microphone microphone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RtcPeer createPeer(List<IceServer> iceServers, RtcPeer.Listener listener) {
            peer.iceServers = iceServers;
            peer.listener = listener;
            return peer;
        }
    };

    private final RelayJoin join = new RelayJoin(
            network,
            rtc,
            "wss://relay.example",
            "ABCDE",
            new RelayJoin.Listener() {
                @Override
                public void joined(RelayWorldAddress address) {
                    joined = address;
                }

                @Override
                public void failed(String reason) {
                    failed = reason;
                }
            },
            () -> now);

    @Test
    void pairsWithTheHostAndHandsOverTheDataChannel() {
        join.start();
        relay.connected();
        ByteBuf hello = next();
        assertThat((int) hello.readUnsignedByte()).isEqualTo(0x00);
        assertThat((int) hello.readUnsignedByte()).isEqualTo(0x02);
        assertThat((int) hello.readUnsignedByte()).isEqualTo(1);
        assertThat(ascii8(hello)).isEqualTo("ABCDE");

        relay.received(message(0x00, 0x02, 0x01, 5, 'A', 'B', 'C', 'D', 'E'));
        ByteBuf servers = Unpooled.buffer();
        servers.writeByte(0x01).writeShort(2);
        servers.writeByte('S');
        ascii16(servers, "stun:stun.example:3478");
        servers.writeByte(0).writeByte(0);
        servers.writeByte('T');
        ascii16(servers, "turn:turn.example:80");
        ascii8(servers, "user");
        ascii8(servers, "pass");
        relay.received(servers.nioBuffer());

        assertThat(peer.iceServers)
                .containsExactly(
                        new IceServer("stun:stun.example:3478", null, null),
                        new IceServer("turn:turn.example:80", "user", "pass"));
        assertThat(peer.channelLabel).isEqualTo("lan");
        assertThat(peer.offered).isTrue();

        peer.listener.localDescription("{\"type\":\"offer\",\"sdp\":\"v=0\"}");
        ByteBuf offer = next();
        assertThat((int) offer.readUnsignedByte()).isEqualTo(0x04);
        assertThat(ascii8(offer)).isEmpty();
        assertThat(bytes16(offer)).isEqualTo("{\"type\":\"offer\",\"sdp\":\"v=0\"}");

        relay.received(signal(0x04, "host1", "{\"type\":\"answer\",\"sdp\":\"v=0 answer\"}"));
        assertThat(peer.remote).isEqualTo("{\"type\":\"answer\",\"sdp\":\"v=0 answer\"}");

        // Candidates go all at once, when gathering is done.
        peer.listener.iceCandidate("candidate:1 1 udp 1 10.0.0.2 5000 typ host", 0);
        peer.listener.iceCandidate("candidate:2 1 udp 1 10.0.0.3 5001 typ host", 0);
        join.tick();
        assertThat(sentToRelay).isEmpty();
        peer.listener.iceGatheringComplete();
        join.tick();
        ByteBuf candidates = next();
        assertThat((int) candidates.readUnsignedByte()).isEqualTo(0x03);
        ascii8(candidates);
        assertThat(IceJson.read(bytes16(candidates)))
                .extracting(IceJson.Candidate::candidate)
                .containsExactly(
                        "candidate:1 1 udp 1 10.0.0.2 5000 typ host", "candidate:2 1 udp 1 10.0.0.3 5001 typ host");

        relay.received(signal(
                0x03,
                "host1",
                "[{\"sdpMLineIndex\":\"0\",\"candidate\":\"candidate:9 1 udp 1 10.0.0.9 6000 typ host\"}]"));
        assertThat(peer.candidates).containsExactly("0:candidate:9 1 udp 1 10.0.0.9 6000 typ host");

        // The channel opens: the relay hears success, and the address takes over the channel.
        peer.channelListener.connected();
        ByteBuf success = next();
        assertThat((int) success.readUnsignedByte()).isEqualTo(0x05);
        assertThat(ascii8(success)).isEqualTo("host1");
        assertThat(relayClosed).isTrue();
        // A moment for an Incari host's hello; Eagler's hosts never speak first.
        assertThat(joined).isNull();
        now += HostHello.WAIT_MILLIS + 1;
        join.tick();
        assertThat(joined).isNotNull();
        assertThat(joined.incariProtocol()).isEqualTo(-1);
        assertThat(failed).isNull();

        peer.channelListener.received(ByteBuffer.wrap(new byte[] {0, 2, 3}));
        List<String> heard = new ArrayList<>();
        joined.open(new NetConnection.Listener() {
            @Override
            public void connected() {
                heard.add("connected");
            }

            @Override
            public void received(ByteBuffer data) {
                heard.add("bytes " + data.remaining());
            }

            @Override
            public void closed(@Nullable Throwable cause) {
                heard.add("closed");
            }
        });
        assertThat(heard).containsExactly("connected", "bytes 3");
    }

    @Test
    void anIncariHostsHelloJoinsAtOnceAndIsntPassedOn() {
        reachOpenChannel();
        assertThat(joined).isNull();
        peer.channelListener.received(HostHello.write(775));
        join.tick();
        assertThat(joined).isNotNull();
        assertThat(joined.incariProtocol()).isEqualTo(775);
        List<String> heard = new ArrayList<>();
        joined.open(new NetConnection.Listener() {
            @Override
            public void connected() {
                heard.add("connected");
            }

            @Override
            public void received(ByteBuffer data) {
                heard.add("bytes " + data.remaining());
            }

            @Override
            public void closed(@Nullable Throwable cause) {}
        });
        assertThat(heard).containsExactly("connected");
    }

    /** A join up to the open data channel, the relay told. */
    private void reachOpenChannel() {
        join.start();
        relay.connected();
        next();
        relay.received(message(0x00, 0x02, 0x01, 5, 'A', 'B', 'C', 'D', 'E'));
        ByteBuf servers = Unpooled.buffer();
        servers.writeByte(0x01).writeShort(0);
        relay.received(servers.nioBuffer());
        peer.listener.localDescription("{\"type\":\"offer\",\"sdp\":\"v=0\"}");
        next();
        relay.received(signal(0x04, "host1", "{\"type\":\"answer\",\"sdp\":\"v=0\"}"));
        peer.listener.iceGatheringComplete();
        join.tick();
        next();
        relay.received(signal(0x03, "host1", "[]"));
        peer.channelListener.connected();
        next();
    }

    @Test
    void aWrongCodeFailsWithTheRelaysReason() {
        join.start();
        relay.connected();
        ByteBuf error = Unpooled.buffer();
        error.writeByte(0xFF).writeByte(0x05);
        ascii16(error, "Incorrect code");
        relay.received(error.nioBuffer());
        assertThat(failed).contains("ABCDE");
    }

    @Test
    void aSilentRelayTimesOut() {
        join.start();
        now += 11_000;
        join.tick();
        assertThat(failed).isEqualTo("The relay didn't answer");
    }

    // --- Helpers -----------------------------------------------------------------------------

    private ByteBuf next() {
        assertThat(sentToRelay).isNotEmpty();
        return Unpooled.wrappedBuffer(sentToRelay.remove(0));
    }

    private static ByteBuffer message(int... bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return ByteBuffer.wrap(out);
    }

    private static ByteBuffer signal(int id, String peer, String text) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(id);
        ascii8(out, peer);
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.writeShort(bytes.length).writeBytes(bytes);
        return out.nioBuffer();
    }

    private static void ascii8(ByteBuf out, String text) {
        out.writeByte(text.length()).writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void ascii16(ByteBuf out, String text) {
        out.writeShort(text.length()).writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String ascii8(ByteBuf in) {
        int length = in.readUnsignedByte();
        return in.readCharSequence(length, StandardCharsets.ISO_8859_1).toString();
    }

    private static String bytes16(ByteBuf in) {
        int length = in.readUnsignedShort();
        return in.readCharSequence(length, StandardCharsets.ISO_8859_1).toString();
    }

    private record FakeConnection(List<ByteBuffer> sent, Runnable onClose) implements NetConnection {
        @Override
        public void send(ByteBuffer data) {
            sent.add(data);
        }

        @Override
        public void close() {
            onClose.run();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public String remoteDescription() {
            return "fake";
        }
    }

    private static final class FakePeer implements RtcPeer {
        List<IceServer> iceServers = List.of();
        RtcPeer.Listener listener;
        NetConnection.Listener channelListener;
        String channelLabel;
        boolean offered;
        String remote;
        final List<String> candidates = new ArrayList<>();

        @Override
        public void sendMicrophone() {}

        @Override
        public NetConnection openDataChannel(String label, NetConnection.Listener listener) {
            channelLabel = label;
            channelListener = listener;
            return new FakeConnection(new ArrayList<>(), () -> {});
        }

        @Override
        public void createOffer() {
            offered = true;
        }

        @Override
        public void setRemoteDescription(String json) {
            remote = json;
        }

        @Override
        public void addIceCandidate(String candidate, int sdpMLineIndex) {
            candidates.add(sdpMLineIndex + ":" + candidate);
        }

        @Override
        public RemoteVoice voice() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }
}
