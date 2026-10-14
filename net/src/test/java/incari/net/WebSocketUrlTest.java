package incari.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebSocketUrlTest {

    @Test
    void defaultsThePortAndPathByScheme() {
        assertThat(WebSocketUrl.parse("ws://play.example.com"))
                .isEqualTo(new WebSocketUrl(false, "play.example.com", 80, "/"));
        assertThat(WebSocketUrl.parse("wss://play.example.com"))
                .isEqualTo(new WebSocketUrl(true, "play.example.com", 443, "/"));
    }

    @Test
    void keepsAnExplicitPortPathAndQuery() {
        WebSocketUrl url = WebSocketUrl.parse("  WSS://Play.Example.com:8443/mc/ws?region=eu#ignored ");

        assertThat(url).isEqualTo(new WebSocketUrl(true, "Play.Example.com", 8443, "/mc/ws?region=eu"));
        assertThat(url.toString()).isEqualTo("wss://Play.Example.com:8443/mc/ws?region=eu");
    }

    @Test
    void aQueryWithoutAPathGetsTheRootPath() {
        assertThat(WebSocketUrl.parse("ws://localhost:8816?x=1").path()).isEqualTo("/?x=1");
    }

    @Test
    void canonicalFormDropsTheDefaultPort() {
        assertThat(WebSocketUrl.parse("ws://localhost:80/").toString()).isEqualTo("ws://localhost/");
        assertThat(WebSocketUrl.parse("wss://localhost:443").toString()).isEqualTo("wss://localhost/");
        assertThat(WebSocketUrl.parse("ws://localhost:8816").toString()).isEqualTo("ws://localhost:8816/");
    }

    @Test
    void readsBracketedIpv6() {
        WebSocketUrl url = WebSocketUrl.parse("ws://[::1]:8816/");

        assertThat(url.host()).isEqualTo("::1");
        assertThat(url.port()).isEqualTo(8816);
        assertThat(url.toString()).isEqualTo("ws://[::1]:8816/");
    }

    @Test
    void acceptsInternationalHostNames() {
        assertThat(WebSocketUrl.parse("wss://bücher.example/").host()).isEqualTo("bücher.example");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ws://",
                "ws://:8816",
                "ws://host:0",
                "ws://host:65536",
                "ws://host:12a",
                "ws://host:",
                "ws://user:pass@host/",
                "ws://::1/",
                "ws://[::1/",
                "ws://[::1]x/",
                "ws://[host]/",
                "ws://ho st/",
                "ws://host/a b",
                "http://host/",
                "host:25565"
            })
    void rejects(String text) {
        assertThatThrownBy(() -> WebSocketUrl.parse(text)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WebSocketUrl.tryParse(text)).isNull();
    }

    @Test
    void recognisesTheSchemeBeforeParsing() {
        assertThat(WebSocketUrl.isWebSocketUrl("ws://")).isTrue();
        assertThat(WebSocketUrl.isWebSocketUrl(" WSS://x")).isTrue();
        assertThat(WebSocketUrl.isWebSocketUrl("localhost:25565")).isFalse();
        assertThat(WebSocketUrl.isWebSocketUrl("wsx://x")).isFalse();
        assertThat(WebSocketUrl.isWebSocketUrl(null)).isFalse();
    }

    @Test
    void anAddressStandsForItsUrlWithoutResolvingIt() {
        WebSocketUrl url = WebSocketUrl.parse("wss://no-such-host.invalid:9443/mc");
        WebSocketAddress address = new WebSocketAddress(url);

        assertThat(address.url()).isEqualTo(url);
        assertThat(address.getHostName()).isEqualTo("no-such-host.invalid");
        assertThat(address.getHostString()).isEqualTo("no-such-host.invalid");
        assertThat(address.getPort()).isEqualTo(9443);
        assertThat(address.toString()).isEqualTo("wss://no-such-host.invalid:9443/mc");
    }
}
