package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A minimal client for a Minecraft server's remote console (RCON), so a browser check can make the
 * server act on the player while the page runs: a dimension change, a transfer, a kick.
 *
 * <p>The protocol is Source RCON: little-endian length, request id and type, then an ASCII body
 * ending in two zero bytes. Type 3 logs in, type 2 runs a command, and the server answers each
 * with the request's id (a failed login answers id -1). A command's reply is read in one packet,
 * which is enough for the short replies checks use.
 */
final class Rcon implements AutoCloseable {

    private static final int LOGIN = 3;
    private static final int COMMAND = 2;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Rcon(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects to {@code host:port} and logs in with {@code password}. */
    static Rcon connect(String address, String password) throws IOException {
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            throw new CliException("RCON address '" + address + "' needs a port (host:port)");
        }
        Socket socket = new Socket();
        socket.connect(
                new InetSocketAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))),
                5000);
        socket.setSoTimeout(10_000);
        Rcon rcon = new Rcon(socket);
        try {
            int id = rcon.send(LOGIN, password);
            if (rcon.receive().id() != id) {
                throw new CliException("RCON login to " + address + " was refused (check the password)");
            }
        } catch (IOException | RuntimeException e) {
            rcon.close();
            throw e;
        }
        return rcon;
    }

    /** Runs a server command and returns the server's reply. */
    String command(String command) throws IOException {
        int id = send(COMMAND, command);
        Packet reply = receive();
        if (reply.id() != id) {
            throw new IOException("RCON reply for request " + reply.id() + ", expected " + id);
        }
        return reply.body();
    }

    private int send(int type, String body) throws IOException {
        int id = nextId++;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + bytes.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(4 + 4 + bytes.length + 2)
                .putInt(id)
                .putInt(type)
                .put(bytes)
                .put((byte) 0)
                .put((byte) 0);
        out.write(packet.array());
        out.flush();
        return id;
    }

    private Packet receive() throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > 1 << 20) {
            throw new IOException("RCON packet of " + length + " bytes");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int id = packet.getInt();
        packet.getInt(); // The type: a reply's is 0, a login's 2, and the id tells them apart already.
        return new Packet(id, new String(bytes, 8, length - 10, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Packet(int id, String body) {}
}
