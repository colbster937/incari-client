package incari.tools.webbuild;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The page's console, read from Chromium's log on a thread of its own so the browser never waits
 * on it. Every message is echoed, queued for {@link #await} and kept for {@link #history}.
 */
final class PageConsole {

    /** What web-classlib's {@code System.exit} prints (the game may prefix it: it logs standard error). */
    private static final String EXITED = "Java exited with status ";

    /** What the page (app-web's index.html) logs when an error escapes Java. */
    private static final String CRASHED = "[Incari] Crashed: ";

    /** Where Chromium's DevTools listen, which it prints at start with {@code --remote-debugging-port}. */
    private static final Pattern DEVTOOLS = Pattern.compile("DevTools listening on (ws://\\S+)");

    // Chromium's stderr form of a page console message, which varies by version:
    // ...:INFO:CONSOLE(12)] "text", source: url (12)   or   ...:INFO:CONSOLE:12] "text", source: ...
    private static final Pattern CONSOLE =
            Pattern.compile(".*:(INFO|WARNING|ERROR):CONSOLE(?:\\(\\d+\\)|:\\d+)?\\] \"(.*)\", source: (.*)");

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final List<String> history = new ArrayList<>();
    private final CompletableFuture<String> devToolsUrl = new CompletableFuture<>();
    private volatile boolean ended;
    private volatile boolean quiet;

    PageConsole(Process process) {
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader stderr =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    Matcher listening = DEVTOOLS.matcher(line);
                    if (listening.find()) {
                        devToolsUrl.complete(listening.group(1));
                    }
                    Matcher console = CONSOLE.matcher(line);
                    if (!console.matches() || console.group(3).startsWith("chrome-extension:")) {
                        continue;
                    }
                    String message = console.group(2).replace("\\n", "\n");
                    if (!quiet) {
                        System.out.println("[console " + console.group(1).toLowerCase(Locale.ROOT) + "] " + message);
                    }
                    synchronized (history) {
                        history.add(message);
                    }
                    messages.add(message);
                }
            } catch (IOException e) {
                // The browser went away; ended says so.
            } finally {
                ended = true;
            }
        });
    }

    /** Stops echoing messages (they're still queued and kept); for long runs with their own report. */
    void quiet() {
        quiet = true;
    }

    /** Every message so far, in order. */
    List<String> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /** Waits for {@code count} messages matching {@code expect}; false (and why) if the game ends or time runs out. */
    boolean await(Pattern expect, int count, long deadline) throws InterruptedException {
        int seen = 0;
        while (true) {
            String message = next(deadline);
            if (message == null) {
                System.out.println("==> Failed: saw " + seen + " of " + count + " messages matching " + expect.pattern()
                        + " before the browser exited or time ran out");
                return false;
            }
            if (crashed(message, seen, count, expect)) {
                return false;
            }
            if (expect.matcher(message).matches() && ++seen >= count) {
                return true;
            }
        }
    }

    /** Lets the page run for {@code millis}; false if the game ends meanwhile. */
    boolean idle(long millis, long deadline) throws InterruptedException {
        long until = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
        while (System.nanoTime() < until) {
            String message = messages.poll(
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())), TimeUnit.MILLISECONDS);
            if (message != null && crashed(message, 0, 0, null)) {
                return false;
            }
        }
        return true;
    }

    String devToolsUrl(long deadline) throws IOException, InterruptedException {
        try {
            return devToolsUrl.get(
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Chromium didn't print its DevTools address", e);
        }
    }

    private @Nullable String next(long deadline) throws InterruptedException {
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            String message = messages.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS);
            if (message != null) {
                return message;
            }
            if (ended && messages.isEmpty()) {
                return null;
            }
        }
    }

    private static boolean crashed(String message, int seen, int count, @Nullable Pattern expect) {
        if (message.contains(EXITED) || message.startsWith(CRASHED)) {
            // The page keeps running after System.exit; waiting on would only run out the clock.
            System.out.println("==> Failed: the game exited"
                    + (expect == null
                            ? ""
                            : " after " + seen + " of " + count + " messages matching " + expect.pattern()));
            return true;
        }
        return false;
    }
}
