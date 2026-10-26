package incari.net.wisp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SrvLookupTest {

    @Test
    void asksForTheMinecraftRecord() {
        byte[] query = SrvLookup.query("_minecraft._tcp.example.com", 0x1234);

        ByteBuffer in = ByteBuffer.wrap(query);
        assertThat(in.getShort() & 0xFFFF).isEqualTo(query.length - 2);
        assertThat(in.getShort() & 0xFFFF).isEqualTo(0x1234);
        in.position(14);
        assertThat(in.get()).isEqualTo((byte) 10);
        assertThat(new String(query, 15, 10, StandardCharsets.US_ASCII)).isEqualTo("_minecraft");
        assertThat(query[query.length - 3]).isEqualTo((byte) 33); // SRV
    }

    @Test
    void readsTheTargetThroughNameCompression() {
        byte[] reply = reply(7, "_minecraft._tcp.example.com", 0, 5, 25599, "play.example.com");

        assertThat(SrvLookup.parse(reply, 7)).isEqualTo(new SrvLookup.Target("play.example.com", 25599));
    }

    @Test
    void picksTheLowestPriority() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, 7, 2);
        question(out, "_minecraft._tcp.example.com");
        answer(out, 10, 0, 1111, "backup.example.com");
        answer(out, 1, 0, 2222, "main.example.com");

        assertThat(SrvLookup.parse(out.toByteArray(), 7)).isEqualTo(new SrvLookup.Target("main.example.com", 2222));
    }

    @Test
    void noRecordOrAnotherQueryGivesNothing() {
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        header(empty, 7, 0);
        question(empty, "_minecraft._tcp.example.com");

        assertThat(SrvLookup.parse(empty.toByteArray(), 7)).isNull();
        assertThat(SrvLookup.parse(reply(8, "_minecraft._tcp.a.com", 0, 0, 1, "b.com"), 7))
                .isNull();
        assertThat(SrvLookup.parse(new byte[] {0, 7, (byte) 0x81}, 7)).isNull();
    }

    @Test
    void hostsThatCantBeNamedSkipTheLookup() {
        assertThat(SrvLookup.recordName("example.com")).isEqualTo("_minecraft._tcp.example.com");
        assertThat(SrvLookup.recordName("a".repeat(64) + ".com")).isNull();
        assertThat(SrvLookup.recordName("example..com")).isNull();
    }

    @Test
    void onlyHostNamesOnTheDefaultPortLookUpSrv() {
        var proxy = incari.net.WebSocketUrl.parse("wss://w.example/");
        assertThat(new WispAddress(proxy, "example.com", 25565).lookUpSrv()).isTrue();
        assertThat(new WispAddress(proxy, "example.com", 25566).lookUpSrv()).isFalse();
        assertThat(new WispAddress(proxy, "203.0.113.4", 25565).lookUpSrv()).isFalse();
        assertThat(new WispAddress(proxy, "2001:db8::1", 25565).lookUpSrv()).isFalse();
    }

    /** A reply with one SRV answer whose name points back at the question's. */
    static byte[] reply(int id, String name, int priority, int weight, int port, String target) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, id, 1);
        question(out, name);
        answer(out, priority, weight, port, target);
        return out.toByteArray();
    }

    private static void header(ByteArrayOutputStream out, int id, int answers) {
        short16(out, id);
        short16(out, 0x8180);
        short16(out, 1);
        short16(out, answers);
        short16(out, 0);
        short16(out, 0);
    }

    private static void question(ByteArrayOutputStream out, String name) {
        name(out, name);
        short16(out, 33);
        short16(out, 1);
    }

    private static void answer(ByteArrayOutputStream out, int priority, int weight, int port, String target) {
        out.write(0xC0); // the question's name, at offset 12
        out.write(12);
        short16(out, 33);
        short16(out, 1);
        short16(out, 0);
        short16(out, 300);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        short16(data, priority);
        short16(data, weight);
        short16(data, port);
        name(data, target);
        short16(out, data.size());
        out.writeBytes(data.toByteArray());
    }

    private static void name(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.", -1)) {
            out.write(label.length());
            out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        out.write(0);
    }

    private static void short16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }
}
