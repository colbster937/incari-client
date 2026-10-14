package incari.tools.wsbridge;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

/**
 * {@code incari-wsbridge [--bind 127.0.0.1] [--listen 8816] [--target 127.0.0.1:25565]}: bridges
 * WebSocket clients on {@code --listen} to a Java server at {@code --target}. Runs until killed.
 * Binds to the loopback interface unless told otherwise; it's a development tool.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        String bind = "127.0.0.1";
        int listen = 8816;
        String target = "127.0.0.1:25565";
        for (int i = 0; i < args.length; i += 2) {
            String value = i + 1 < args.length ? args[i + 1] : "";
            switch (args[i]) {
                case "--bind" -> bind = value;
                case "--listen" -> listen = Integer.parseInt(value);
                case "--target" -> target = value;
                default -> {
                    System.err.println("Usage: incari-wsbridge [--bind host] [--listen port] [--target host:port]");
                    System.exit(2);
                }
            }
        }
        int colon = target.lastIndexOf(':');
        InetSocketAddress server = colon < 0
                ? new InetSocketAddress(target, 25565)
                : new InetSocketAddress(target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)));

        WebSocketBridge bridge =
                WebSocketBridge.start(new InetSocketAddress(bind, listen), server, System.out::println);
        System.out.println("Bridging ws://" + bind + ":" + bridge.address().getPort() + "/ to " + server);
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::close));
        new CountDownLatch(1).await();
    }
}
