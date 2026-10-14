package incari.classlib.concurrent;

/**
 * Where the browser build's notifications go. The web build's {@code MonitorElision} drops the
 * monitors of methods that notify but never wait (their bodies can't suspend, so they already run
 * exclusively in the page) and routes each {@code notify}/{@code notifyAll} here, where the monitor
 * is taken just for the call, as {@code notify} requires. The build takes these two monitors
 * without suspending, so a method that only notifies stays an ordinary function. The web build
 * refers to this class by name.
 */
public final class Wakeups {

    private Wakeups() {}

    public static void notify(Object monitor) {
        synchronized (monitor) {
            monitor.notify();
        }
    }

    public static void notifyAll(Object monitor) {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}
