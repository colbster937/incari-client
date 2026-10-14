package incari.classlib.substitutes.java.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The java.net substitutes behave as the JDK's do for what a page can do (IP literals, no lookups),
 * and every member they declare is the JDK's, so the browser build links them by name.
 */
class NetSubstitutesTest {

    private static final String SUBSTITUTES = "incari.classlib.substitutes.";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.1.2.3",
                "255.255.255.255",
                "0.0.0.0",
                "::1",
                "::",
                "2001:db8::8a2e:370:7334",
                "fe80::1",
                "::ffff:192.0.2.1",
                "1:2:3:4:5:6:7:8",
                "[::1]"
            })
    void readsIpLiteralsAsTheJdkDoes(String literal) throws Exception {
        InetAddress jdk = InetAddress.getByName(literal);
        TInetAddress ours = TInetAddress.getByName(literal);

        assertThat(ours.getAddress()).isEqualTo(jdk.getAddress());
        assertThat(ours.getHostAddress()).isEqualTo(jdk.getHostAddress());
        assertThat(ours.isAnyLocalAddress()).isEqualTo(jdk.isAnyLocalAddress());
        assertThat(ours.isLoopbackAddress()).isEqualTo(jdk.isLoopbackAddress());
        assertThat(ours.isLinkLocalAddress()).isEqualTo(jdk.isLinkLocalAddress());
        assertThat(ours.isSiteLocalAddress()).isEqualTo(jdk.isSiteLocalAddress());
        assertThat(ours.isMulticastAddress()).isEqualTo(jdk.isMulticastAddress());
        assertThat(ours instanceof TInet4Address).isEqualTo(jdk instanceof Inet4Address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"256.1.1.1", "1.2.3", "1:2:3:4:5:6:7:8:9", "1::2::3", "::g", "example.com"})
    void anythingElseIsAnUnknownHost(String host) {
        assertThatThrownBy(() -> TInetAddress.getByName(host)).isInstanceOf(TUnknownHostException.class);
    }

    @Test
    void anAddressMadeWithANameKeepsItWithoutALookup() throws Exception {
        byte[] bytes = {(byte) 203, 0, 113, 7};
        InetAddress jdk = InetAddress.getByAddress("mc.example.com", bytes);
        TInetAddress ours = TInetAddress.getByAddress("mc.example.com", bytes);

        assertThat(ours.getHostName()).isEqualTo(jdk.getHostName());
        assertThat(ours.toString()).isEqualTo(jdk.toString());
        assertThat(ours).isEqualTo(TInetAddress.getByAddress(bytes));
        assertThatThrownBy(() -> TInetAddress.getByAddress(new byte[5])).isInstanceOf(TUnknownHostException.class);
    }

    @Test
    @SuppressWarnings("AddressSelection") // The constructor from a literal is what the game uses.
    void socketAddressesPrintAndCompareAsTheJdksDo() throws Exception {
        byte[] bytes = {10, 0, 0, 2};
        compare(
                new InetSocketAddress(InetAddress.getByAddress("host", bytes), 25565),
                new TInetSocketAddress(TInetAddress.getByAddress("host", bytes), 25565));
        compare(new InetSocketAddress("127.0.0.1", 8816), new TInetSocketAddress("127.0.0.1", 8816));
        compare(
                InetSocketAddress.createUnresolved("mc.example.com", 1),
                TInetSocketAddress.createUnresolved("mc.example.com", 1));
        compare(new InetSocketAddress(25577), new TInetSocketAddress(25577));
        assertThat(new TInetSocketAddress("no-such-host.invalid", 5).isUnresolved())
                .isTrue();
        assertThatThrownBy(() -> new TInetSocketAddress(65536)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void compare(InetSocketAddress jdk, TInetSocketAddress ours) {
        assertThat(ours.toString()).isEqualTo(jdk.toString());
        assertThat(ours.getHostString()).isEqualTo(jdk.getHostString());
        assertThat(ours.getPort()).isEqualTo(jdk.getPort());
        assertThat(ours.isUnresolved()).isEqualTo(jdk.isUnresolved());
        assertThat(ours.hashCode()).isEqualTo(jdk.hashCode());
    }

    @Test
    void declaresOnlyTheJdksMembers() {
        assertJdkMembers(TSocketAddress.class, SocketAddress.class);
        assertJdkMembers(TInetAddress.class, InetAddress.class);
        assertJdkMembers(TInet4Address.class, Inet4Address.class);
        assertJdkMembers(TInet6Address.class, Inet6Address.class);
        assertJdkMembers(TInetSocketAddress.class, InetSocketAddress.class);
        assertJdkMembers(TUnknownHostException.class, UnknownHostException.class);
        assertJdkMembers(TSocketException.class, SocketException.class);
        assertJdkMembers(TConnectException.class, ConnectException.class);
    }

    private static void assertJdkMembers(Class<?> ours, Class<?> jdk) {
        assertThat(jdkName(ours)).isEqualTo(jdk.getName());
        assertThat(jdkName(ours.getSuperclass())).isEqualTo(jdk.getSuperclass().getName());
        Map<String, String> jdkMembers = members(jdk);
        for (Map.Entry<String, String> member : members(ours).entrySet()) {
            assertThat(jdkMembers.get(member.getKey()))
                    .as("the JDK's %s.%s", jdk.getSimpleName(), member.getKey())
                    .isEqualTo(member.getValue());
        }
    }

    /** Each public or protected member, by name and parameters, to its return type and modifiers. */
    private static Map<String, String> members(Class<?> type) {
        Map<String, String> members = new TreeMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (visible(method) && !method.isSynthetic()) {
                members.put(
                        describe(method, method.getName()),
                        jdkName(method.getReturnType()) + " " + Modifier.toString(method.getModifiers()));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (visible(constructor)) {
                members.put(describe(constructor, "<init>"), Modifier.toString(constructor.getModifiers()));
            }
        }
        return members;
    }

    private static boolean visible(Executable member) {
        return Modifier.isPublic(member.getModifiers()) || Modifier.isProtected(member.getModifiers());
    }

    private static String describe(Executable member, String name) {
        return name
                + Arrays.stream(member.getParameterTypes())
                        .map(NetSubstitutesTest::jdkName)
                        .collect(Collectors.joining(",", "(", ")"));
    }

    private static String jdkName(Class<?> type) {
        if (type.isArray()) {
            return jdkName(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        if (!name.startsWith(SUBSTITUTES)) {
            return name;
        }
        String rest = name.substring(SUBSTITUTES.length());
        int dot = rest.lastIndexOf('.');
        return rest.substring(0, dot + 1) + rest.substring(dot + 2);
    }
}
