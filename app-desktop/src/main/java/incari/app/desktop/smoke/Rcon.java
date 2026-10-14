package incari.app.desktop.smoke;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A game server's remote console, for smoke scenarios that set up the world: one command per
 * connection, which is plenty for a test. The protocol: little-endian length, id and type, the
 * body, two zero bytes; type 3 logs in, type 2 runs a command.
 */
final class Rcon {

    private final String host;
    private final int port;
    private final String password;

    Rcon(String address, String password) {
        int colon = address.lastIndexOf(':');
        this.host = address.substring(0, colon);
        this.port = Integer.parseInt(address.substring(colon + 1));
        this.password = password;
    }

    /** Runs {@code command} and returns the server's reply. */
    String run(String command) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.write(packet(1, 3, password));
            if (read(in).id == -1) {
                throw new IOException("RCON refused the password");
            }
            out.write(packet(2, 2, command));
            return read(in).body;
        }
    }

    private record Reply(int id, String body) {}

    private static byte[] packet(int id, int type, String body) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + text.length)
                .putInt(id)
                .putInt(type)
                .put(text)
                .put((byte) 0)
                .put((byte) 0);
        return buffer.array();
    }

    private static Reply read(DataInputStream in) throws IOException {
        byte[] head = new byte[12];
        in.readFully(head);
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getInt();
        int id = buffer.getInt();
        buffer.getInt();
        byte[] body = new byte[length - 8];
        in.readFully(body);
        return new Reply(id, new String(body, 0, Math.max(0, body.length - 2), StandardCharsets.UTF_8));
    }
}
