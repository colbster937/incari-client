package incari.client.singleplayer;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.net.NetConnection;
import incari.platform.api.threads.PlatformChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ChannelLinkTest {

    /** A channel that records what it's asked to send. */
    private static final class Recorded implements PlatformChannel {
        final List<Integer> kinds = new ArrayList<>();
        final List<byte[]> messages = new ArrayList<>();

        @Override
        public void send(int kind, byte[] bytes, int offset, int length) {
            kinds.add(kind);
            messages.add(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        }

        @Override
        public @Nullable Message receive() {
            return null;
        }

        @Override
        public @Nullable Message poll() {
            return null;
        }

        @Override
        public void close() {}
    }

    /** What the game's channel hears. */
    private static final class Heard implements NetConnection.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void connected() {
            events.add("connected");
        }

        @Override
        public void received(ByteBuffer data) {
            events.add("packet " + data.get(0));
        }

        @Override
        public void closed(@Nullable Throwable cause) {
            events.add("closed");
        }
    }

    @Test
    void packetsBeforeTheChannelOpensAreKeptForIt() {
        ChannelLink link = new ChannelLink(new Recorded(), 2, 6);
        link.deliver(new byte[] {1});
        link.deliver(new byte[] {2});
        Heard heard = new Heard();

        link.open(heard);
        link.deliver(new byte[] {3});

        assertThat(heard.events).containsExactly("connected", "packet 1", "packet 2", "packet 3");
    }

    @Test
    void aCloseBeforeTheChannelOpensComesAfterThePackets() {
        ChannelLink link = new ChannelLink(new Recorded(), 2, 6);
        link.deliver(new byte[] {1});
        link.closedByOtherEnd();
        Heard heard = new Heard();

        link.open(heard);

        assertThat(heard.events).containsExactly("connected", "packet 1", "closed");
        assertThat(link.isOpen()).isFalse();
    }

    @Test
    void aFlushsSendsGoOutAsOneMessageOfItsKind() {
        Recorded channel = new Recorded();
        ChannelLink link = new ChannelLink(channel, 2, 6);
        link.open(new Heard());

        link.send(ByteBuffer.wrap(new byte[] {1, 2}));
        link.send(ByteBuffer.wrap(new byte[] {3}));
        assertThat(channel.kinds).isEmpty();
        link.flush();
        link.flush();
        link.send(ByteBuffer.wrap(new byte[40_000]));
        link.flush();

        assertThat(channel.kinds).containsExactly(2, 2);
        assertThat(channel.messages.get(0)).containsExactly(1, 2, 3);
        assertThat(channel.messages.get(1)).hasSize(40_000);
    }

    @Test
    void closingSendsWhatWasntFlushedThenTellsTheOtherEnd() {
        Recorded channel = new Recorded();
        ChannelLink link = new ChannelLink(channel, 2, 6);
        link.open(new Heard());

        link.send(ByteBuffer.wrap(new byte[] {9}));
        link.close();
        link.send(ByteBuffer.wrap(new byte[] {9}));
        link.flush();

        assertThat(channel.kinds).containsExactly(2, 6);
        assertThat(channel.messages.get(0)).containsExactly(9);
    }
}
