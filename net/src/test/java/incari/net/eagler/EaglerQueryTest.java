package incari.net.eagler;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EaglerQueryTest {

    private final FakeNetwork network = new FakeNetwork();
    private final List<String> results = new ArrayList<>();
    private final EaglerQuery.Callback callback = new EaglerQuery.Callback() {
        @Override
        public void answered(String json, byte @Nullable [] icon, long latencyMillis) {
            results.add("answered " + json + " icon=" + (icon == null ? "none" : icon.length) + " " + latencyMillis);
        }

        @Override
        public void failed(String reason) {
            results.add("failed " + reason);
        }
    };

    @Test
    void asksForTheMotdAndTakesTheIconThatFollows() {
        long[] now = {100};
        EaglerQuery.motd(network, "ws://example/", () -> now[0], callback);
        assertThat(network.texts).containsExactly("Accept: motd");

        network.listener().connected();
        now[0] = 142;
        network.listener().receivedText("{\"type\":\"motd\",\"data\":{\"icon\":true}}");
        assertThat(results).isEmpty();
        network.listener().received(ByteBuffer.allocate(EaglerQuery.ICON_BYTES));

        assertThat(results).containsExactly("answered {\"type\":\"motd\",\"data\":{\"icon\":true}} icon=16384 42");
        assertThat(network.closed).isTrue();
    }

    @Test
    void anAnswerWithoutAnIconIsDoneAtOnce() {
        EaglerQuery.motd(network, "ws://example/", () -> 0, callback);
        network.listener().connected();
        network.listener().receivedText("{\"data\":{\"icon\":false}}");

        assertThat(results).containsExactly("answered {\"data\":{\"icon\":false}} icon=none 0");
    }

    @Test
    void aServerThatClosesWithoutAnsweringFails() {
        EaglerQuery.motd(network, "ws://example/", () -> 0, callback);
        network.listener().closed(null);

        assertThat(results).containsExactly("failed The server closed the connection without answering");
    }

    /** One WebSocket, driven by the test. */
    private static final class FakeNetwork implements PlatformNetwork {
        final List<String> texts = new ArrayList<>();

        NetConnection.@Nullable Listener listener;

        boolean closed;

        NetConnection.Listener listener() {
            NetConnection.Listener current = listener;
            assertThat(current).isNotNull();
            return current;
        }

        @Override
        public NetConnection connect(String host, int port, NetConnection.Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
            this.listener = listener;
            return new NetConnection() {
                @Override
                public void send(ByteBuffer data) {}

                @Override
                public void sendText(String text) {
                    texts.add(text);
                }

                @Override
                public void close() {
                    closed = true;
                }

                @Override
                public boolean isOpen() {
                    return !closed;
                }

                @Override
                public String remoteDescription() {
                    return url;
                }
            };
        }
    }
}
