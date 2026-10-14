package incari.classlib.substitutes.java.net;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.net.InetAddress}, which TeaVM lacks: an IP address, with the host name it was made
 * from if any. A page can't look names up (its WebSockets resolve their own hosts), so {@link
 * #getByName} only reads IP literals and "localhost", and a name it can't read fails as an
 * unknown host, as a failed DNS lookup would.
 */
public class TInetAddress implements Serializable {

    @Serial
    private static final long serialVersionUID = 3286316764910316507L;

    private final byte[] address;
    private final @Nullable String hostName;

    TInetAddress(byte[] address, @Nullable String hostName) {
        this.address = address;
        this.hostName = hostName;
    }

    /** An address of the right kind for {@code address}'s length, with no lookup. */
    public static TInetAddress getByAddress(@Nullable String host, byte[] address) throws TUnknownHostException {
        String name = host == null || host.isEmpty() ? null : stripBrackets(host);
        if (address.length == 4) {
            return new TInet4Address(address.clone(), name);
        }
        if (address.length == 16) {
            if (isIpv4Mapped(address)) {
                // As the JDK does: ::ffff:a.b.c.d is the IPv4 address a.b.c.d.
                return new TInet4Address(Arrays.copyOfRange(address, 12, 16), name);
            }
            return new TInet6Address(address.clone(), name);
        }
        throw new TUnknownHostException("addr is of illegal length");
    }

    public static TInetAddress getByAddress(byte[] address) throws TUnknownHostException {
        return getByAddress(null, address);
    }

    /**
     * Reads an IP literal, or "localhost". There is no DNS in a page: any other name is an
     * unknown host.
     */
    public static TInetAddress getByName(@Nullable String host) throws TUnknownHostException {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return getLoopbackAddress();
        }
        byte[] literal = IpLiterals.parse(stripBrackets(host));
        if (literal == null) {
            throw new TUnknownHostException(host + ": a browser can't look up host names");
        }
        return getByAddress(null, literal);
    }

    public static TInetAddress[] getAllByName(@Nullable String host) throws TUnknownHostException {
        return new TInetAddress[] {getByName(host)};
    }

    public static TInetAddress getLoopbackAddress() {
        return new TInet4Address(new byte[] {127, 0, 0, 1}, "localhost");
    }

    public static TInetAddress getLocalHost() throws TUnknownHostException {
        return getLoopbackAddress();
    }

    public byte[] getAddress() {
        return address.clone();
    }

    /** The name this address was made with, or its IP literal: there's no reverse lookup. */
    public String getHostName() {
        return hostName != null ? hostName : getHostAddress();
    }

    /** The name this address was made with, if any (for {@code InetSocketAddress.getHostString}). */
    @Nullable
    String hostNameOrNull() {
        return hostName;
    }

    public String getCanonicalHostName() {
        return getHostName();
    }

    public String getHostAddress() {
        return IpLiterals.format(address);
    }

    public boolean isAnyLocalAddress() {
        for (byte b : address) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isLoopbackAddress() {
        if (address.length == 4) {
            return address[0] == 127;
        }
        for (int i = 0; i < 15; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[15] == 1;
    }

    public boolean isLinkLocalAddress() {
        return address.length == 4
                ? (address[0] & 0xFF) == 169 && (address[1] & 0xFF) == 254
                : (address[0] & 0xFF) == 0xFE && (address[1] & 0xC0) == 0x80;
    }

    public boolean isSiteLocalAddress() {
        if (address.length == 16) {
            return (address[0] & 0xFF) == 0xFE && (address[1] & 0xC0) == 0xC0;
        }
        int first = address[0] & 0xFF;
        int second = address[1] & 0xFF;
        return first == 10 || (first == 172 && (second & 0xF0) == 16) || (first == 192 && second == 168);
    }

    public boolean isMulticastAddress() {
        return address.length == 4 ? (address[0] & 0xF0) == 0xE0 : (address[0] & 0xFF) == 0xFF;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TInetAddress that && Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        return (hostName != null ? hostName : "") + "/" + getHostAddress();
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    }

    private static String stripBrackets(String host) {
        return host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1)
                : host;
    }

    /** Reading and writing IP literals; nested, since top-level classes here are substitutes. */
    static final class IpLiterals {

        private IpLiterals() {}

        /** The bytes of an IPv4 or IPv6 literal, or null if {@code text} is neither. */
        static byte @Nullable [] parse(String text) {
            return text.indexOf(':') >= 0 ? parseIpv6(text) : parseIpv4(text);
        }

        static byte @Nullable [] parseIpv4(String text) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int value = parseDecimal(parts[i]);
                if (value < 0 || value > 255) {
                    return null;
                }
                bytes[i] = (byte) value;
            }
            return bytes;
        }

        static byte @Nullable [] parseIpv6(String text) {
            int zone = text.indexOf('%');
            String body = zone < 0 ? text : text.substring(0, zone);
            int gap = body.indexOf("::");
            if (gap >= 0 && body.indexOf("::", gap + 1) >= 0) {
                return null;
            }
            String head = gap < 0 ? body : body.substring(0, gap);
            String tail = gap < 0 ? "" : body.substring(gap + 2);
            int[] headGroups = groups(head);
            int[] tailGroups = groups(tail);
            if (headGroups == null || tailGroups == null) {
                return null;
            }
            int total = headGroups.length + tailGroups.length;
            if (gap < 0 ? total != 8 : total > 7) {
                return null;
            }
            byte[] bytes = new byte[16];
            for (int i = 0; i < headGroups.length; i++) {
                bytes[2 * i] = (byte) (headGroups[i] >> 8);
                bytes[2 * i + 1] = (byte) headGroups[i];
            }
            int start = 8 - tailGroups.length;
            for (int i = 0; i < tailGroups.length; i++) {
                bytes[2 * (start + i)] = (byte) (tailGroups[i] >> 8);
                bytes[2 * (start + i) + 1] = (byte) tailGroups[i];
            }
            return bytes;
        }

        /** Colon-separated 16-bit groups; a trailing dotted IPv4 part counts as two. */
        private static int @Nullable [] groups(String text) {
            if (text.isEmpty()) {
                return new int[0];
            }
            String[] parts = text.split(":", -1);
            int[] out = new int[parts.length + 1];
            int count = 0;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (i == parts.length - 1 && part.indexOf('.') >= 0) {
                    byte[] ipv4 = parseIpv4(part);
                    if (ipv4 == null) {
                        return null;
                    }
                    out[count++] = ((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF);
                    out[count++] = ((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF);
                    continue;
                }
                if (part.isEmpty() || part.length() > 4) {
                    return null;
                }
                int value = 0;
                for (int j = 0; j < part.length(); j++) {
                    int digit = Character.digit(part.charAt(j), 16);
                    if (digit < 0) {
                        return null;
                    }
                    value = value * 16 + digit;
                }
                out[count++] = value;
            }
            return Arrays.copyOf(out, count);
        }

        private static int parseDecimal(String text) {
            if (text.isEmpty() || text.length() > 3) {
                return -1;
            }
            int value = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
                value = value * 10 + (c - '0');
            }
            return value;
        }

        static String format(byte[] address) {
            StringBuilder out = new StringBuilder();
            if (address.length == 4) {
                for (int i = 0; i < 4; i++) {
                    if (i > 0) {
                        out.append('.');
                    }
                    out.append(address[i] & 0xFF);
                }
                return out.toString();
            }
            // The JDK's form: every group, in lower-case hex without leading zeros, no "::".
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    out.append(':');
                }
                out.append(Integer.toHexString(((address[2 * i] & 0xFF) << 8) | (address[2 * i + 1] & 0xFF)));
            }
            return out.toString();
        }
    }
}
