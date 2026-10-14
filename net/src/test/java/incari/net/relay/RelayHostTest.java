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

/** Sharing a world against a scripted relay and a fake WebRTC peer (ADR 0062). */
class RelayHostTest {

    private long now = 1000;
    private final List<ByteBuffer> sentToRelay = new ArrayList<>();
    private NetConnection.@Nullable Listener relay;
    private final List<Peer> peers = new ArrayList<>();
    private @Nullable String code;
    private @Nullable String stopped;
    private final List<String> guests = new ArrayList<>();
    private final List<String> heardFromGuest = new ArrayList<>();

    private final PlatformNetwork network = new PlatformNetwork() {
        @Override
        public NetConnection connect(String host, int port, NetConnection.Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
            relay = listener;
            return new Sent(sentToRelay);
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
            Peer peer = new Peer(listener);
            peers.add(peer);
            return peer;
        }
    };

    private final RelayHost host = new RelayHost(
            network,
            rtc,
            "wss://relay.example",
            "My World",
            775,
            new RelayHost.Listener() {
                @Override
                public void shared(String given) {
                    code = given;
                }

                @Override
                public NetConnection.Listener guest(String clientId, NetConnection channel) {
                    guests.add(clientId);
                    return new NetConnection.Listener() {
                        @Override
                        public void connected() {
                            heardFromGuest.add("connected");
                        }

                        @Override
                        public void received(ByteBuffer data) {
                            heardFromGuest.add("bytes " + data.remaining());
                        }

                        @Override
                        public void closed(@Nullable Throwable cause) {
                            heardFromGuest.add("closed");
                        }
                    };
                }

                @Override
                public void stopped(String reason) {
                    stopped = reason;
                }
            },
            () -> now);

    @Test
    void sharesAnswersAGuestAndHandsItsChannelOver() {
        host.start();
        relay.connected();
        ByteBuf hello = next();
        assertThat((int) hello.readUnsignedByte()).isEqualTo(0x00);
        assertThat((int) hello.readUnsignedByte()).isEqualTo(0x01);
        assertThat((int) hello.readUnsignedByte()).isEqualTo(1);
        assertThat(ascii8(hello)).isEqualTo("My World;0");

        relay.received(message(0x00, 0x01, 0x01, 5, 'a', 'b', 'c', 'd', 'e'));
        assertThat(code).isEqualTo("abcde");
        relay.received(message(0x01, 0x00, 0x00));

        relay.received(withAscii8(0x02, "client1"));
        assertThat(peers).hasSize(1);
        Peer peer = peers.get(0);

        relay.received(signal(0x04, "client1", "{\"type\":\"offer\",\"sdp\":\"v=0\"}"));
        assertThat(peer.remote).isEqualTo("{\"type\":\"offer\",\"sdp\":\"v=0\"}");
        peer.listener.localDescription("{\"type\":\"answer\",\"sdp\":\"v=0 answer\"}");
        ByteBuf answer = next();
        assertThat((int) answer.readUnsignedByte()).isEqualTo(0x04);
        assertThat(ascii8(answer)).isEqualTo("client1");

        peer.listener.iceCandidate("candidate:1 1 udp 1 10.0.0.2 5000 typ host", 0);
        relay.received(signal(
                0x03,
                "client1",
                "[{\"sdpMLineIndex\":\"0\",\"candidate\":\"candidate:9 1 udp 1 10.0.0.9 6000 typ host\"}]"));
        assertThat(peer.candidates).containsExactly("0:candidate:9 1 udp 1 10.0.0.9 6000 typ host");
        // Ours go once gathering is done, or a moment after theirs came.
        host.tick();
        assertThat(sentToRelay).isEmpty();
        now += 2_001;
        host.tick();
        ByteBuf ours = next();
        assertThat((int) ours.readUnsignedByte()).isEqualTo(0x03);
        assertThat(ascii8(ours)).isEqualTo("client1");

        // The guest's channel opens: the hello goes first, then the world hears the guest.
        List<ByteBuffer> sentToGuest = new ArrayList<>();
        NetConnection.Listener[] channel = new NetConnection.Listener[1];
        peer.listener.dataChannel("lan", listener -> {
            channel[0] = listener;
            return new Sent(sentToGuest);
        });
        channel[0].connected();
        assertThat(sentToGuest).hasSize(1);
        assertThat(HostHello.read(sentToGuest.get(0))).isEqualTo(775);
        assertThat(guests).containsExactly("client1");
        channel[0].received(ByteBuffer.wrap(new byte[] {0, 1, 2}));
        assertThat(heardFromGuest).containsExactly("connected", "bytes 3");
        // Connected guests no longer time out.
        now += 60_000;
        host.tick();
        assertThat(stopped).isNull();
        assertThat(peer.closed).isFalse();
    }

    @Test
    void aRelayThatGivesNoCodeStops() {
        host.start();
        relay.connected();
        now += 16_000;
        host.tick();
        assertThat(stopped).isEqualTo("The relay didn't give the world a code");
    }

    @Test
    void aGuestThatNeverConnectsIsDropped() {
        host.start();
        relay.connected();
        relay.received(message(0x00, 0x01, 0x01, 1, 'x'));
        relay.received(withAscii8(0x02, "slow"));
        now += 16_000;
        host.tick();
        assertThat(peers.get(0).closed).isTrue();
        assertThat(stopped).isNull();
    }

    @Test
    void brokenEscapesInAGuestsCandidatesAreLeftOut() {
        host.start();
        relay.connected();
        relay.received(message(0x00, 0x01, 0x01, 1, 'x'));
        relay.received(withAscii8(0x02, "odd"));
        relay.received(signal(0x03, "odd", "[{\"sdpMLineIndex\":\"0\",\"candidate\":\"a\\uZZ\\u00e9\\u12\"}]"));
        assertThat(peers.get(0).candidates).containsExactly("0:aZZé12");
        assertThat(peers.get(0).closed).isFalse();
    }

    @Test
    void aGuestWhoseCandidatesCantBeTakenFailsAloneAndTheWorldStaysShared() {
        host.start();
        relay.connected();
        relay.received(message(0x00, 0x01, 0x01, 1, 'x'));
        relay.received(withAscii8(0x02, "bad"));
        relay.received(withAscii8(0x02, "good"));

        relay.received(signal(0x03, "bad", "[{\"sdpMLineIndex\":\"0\",\"candidate\":\"refused\"}]"));
        assertThat(peers.get(0).closed).isTrue();
        assertThat(stopped).isNull();

        relay.received(signal(0x03, "good", "[{\"sdpMLineIndex\":\"0\",\"candidate\":\"fine\"}]"));
        assertThat(peers.get(1).candidates).containsExactly("0:fine");
        assertThat(peers.get(1).closed).isFalse();
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

    private static ByteBuffer withAscii8(int id, String text) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(id).writeByte(text.length()).writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
        return out.nioBuffer();
    }

    private static ByteBuffer signal(int id, String peer, String text) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(id).writeByte(peer.length()).writeBytes(peer.getBytes(StandardCharsets.ISO_8859_1));
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.writeShort(bytes.length).writeBytes(bytes);
        return out.nioBuffer();
    }

    private static String ascii8(ByteBuf in) {
        int length = in.readUnsignedByte();
        return in.readCharSequence(length, StandardCharsets.ISO_8859_1).toString();
    }

    private record Sent(List<ByteBuffer> sent) implements NetConnection {
        @Override
        public void send(ByteBuffer data) {
            sent.add(data);
        }

        @Override
        public void close() {}

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public String remoteDescription() {
            return "fake";
        }
    }

    private static final class Peer implements RtcPeer, RemoteVoice {
        final RtcPeer.Listener listener;
        String remote;
        boolean closed;
        final List<String> candidates = new ArrayList<>();

        Peer(RtcPeer.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void sendMicrophone() {}

        @Override
        public NetConnection openDataChannel(String label, NetConnection.Listener channelListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOffer() {}

        @Override
        public void setRemoteDescription(String json) {
            remote = json;
        }

        @Override
        public void addIceCandidate(String candidate, int sdpMLineIndex) {
            if (candidate.contains("refused")) {
                // As a browser may, for a candidate it won't take.
                throw new IllegalArgumentException("bad candidate");
            }
            candidates.add(sdpMLineIndex + ":" + candidate);
        }

        @Override
        public RemoteVoice voice() {
            return this;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void setVolume(float volume) {}

        @Override
        public void setPan(float pan) {}

        @Override
        public float level() {
            return 0;
        }
    }
}
