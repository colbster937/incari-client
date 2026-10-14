package incari.tools.webbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Just enough of the Chrome DevTools Protocol to look at the page and use it like a player:
 * screenshots, and mouse and keyboard input the page receives as real, trusted events. Connects to
 * the browser endpoint Chromium prints ({@code --remote-debugging-port}) and attaches to the page.
 */
final class DevTools implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 30;

    private final WebSocket socket;
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    // Events (messages without an id) that a step waits for: file choosers, downloads.
    private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    // A trace's events as they arrive (Tracing.dataCollected), until the trace ends.
    private final Queue<JsonObject> traceEvents = new ConcurrentLinkedQueue<>();
    // Where a heap snapshot's chunks go as they arrive (HeapProfiler.addHeapSnapshotChunk), while one is taken.
    private volatile @Nullable Writer snapshotSink;
    // The page's console, when collected over the protocol (collectConsole): for a browser this tool
    // didn't start, whose log it can't read.
    private final Queue<String> console = new ConcurrentLinkedQueue<>();
    /** The page's workers attached so far ({@link #attachWorkers}), by session: their names and addresses. */
    private final Map<String, String> workers = new ConcurrentHashMap<>();
    /** The workers' sessions in the order they attached, the latest last. */
    private final Queue<String> workerOrder = new ConcurrentLinkedQueue<>();

    private final AtomicInteger ids = new AtomicInteger();
    private @Nullable String session;
    /** The tab this connection opened ({@link #openTab}), closed with it; null for a page it found. */
    private @Nullable String openedTab;

    private DevTools(WebSocket socket) {
        this.socket = socket;
    }

    /** Connects to {@code browserUrl} ({@code ws://127.0.0.1:<port>/devtools/browser/<id>}) and attaches to its page. */
    static DevTools connect(String browserUrl) throws IOException, InterruptedException {
        return connect(browserUrl, null);
    }

    /**
     * Connects to {@code browserUrl} and attaches to a new tab it opens on {@code url}, leaving the
     * browser's other tabs alone: for a browser this tool didn't start (a Chromebook's, which the
     * benchmark runner drives over the network).
     */
    static DevTools openTab(String browserUrl, String url) throws IOException, InterruptedException {
        return connect(browserUrl, url);
    }

    private static DevTools connect(String browserUrl, @Nullable String newTab)
            throws IOException, InterruptedException {
        Listener listener = new Listener();
        WebSocket socket;
        try {
            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(browserUrl), listener)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Couldn't connect to the browser's DevTools at " + browserUrl, e);
        }
        DevTools devTools = new DevTools(socket);
        listener.devTools = devTools;
        String page = newTab != null ? devTools.createTab(newTab) : devTools.firstPage();
        if (page == null) {
            throw new IOException("The browser has no page to attach to");
        }
        if (newTab != null) {
            devTools.openedTab = page;
        }
        JsonObject attach = new JsonObject();
        attach.addProperty("targetId", page);
        attach.addProperty("flatten", true);
        devTools.session =
                devTools.call("Target.attachToTarget", attach).get("sessionId").getAsString();
        return devTools;
    }

    private String createTab(String url) throws IOException, InterruptedException {
        JsonObject create = new JsonObject();
        create.addProperty("url", url);
        return call("Target.createTarget", create).get("targetId").getAsString();
    }

    private @Nullable String firstPage() throws IOException, InterruptedException {
        JsonArray targets = call("Target.getTargets", new JsonObject()).getAsJsonArray("targetInfos");
        String page = null;
        for (JsonElement target : targets) {
            JsonObject info = target.getAsJsonObject();
            if (info.get("type").getAsString().equals("page")) {
                page = info.get("targetId").getAsString();
                break;
            }
        }
        return page;
    }

    /**
     * Collects the page's console messages from now on ({@link #console}): a browser this tool
     * started logs them to its own output, but a remote one's can only be heard over the protocol.
     */
    void collectConsole() throws IOException, InterruptedException {
        call("Runtime.enable", new JsonObject());
    }

    /** The console messages collected so far ({@link #collectConsole}), one line each. */
    List<String> console() {
        return List.copyOf(console);
    }

    /**
     * Attaches to the page's workers from now on, each a session of its own, found by its name or
     * its script's address ({@link #workerSession}).
     */
    void attachWorkers() throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("autoAttach", true);
        params.addProperty("waitForDebuggerOnStart", false);
        params.addProperty("flatten", true);
        call("Target.setAutoAttach", params);
    }

    /**
     * The session of the latest attached worker still running whose name or script's address
     * contains {@code text}, or null.
     */
    @Nullable
    String workerSession(String text) {
        String found = null;
        for (String session : workerOrder) {
            String about = workers.get(session);
            if (about != null && about.contains(text)) {
                found = session;
            }
        }
        return found;
    }

    /** {@link #evaluate(String)} in a worker's session, without a gesture. */
    String evaluateIn(String worker, String expression) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);
        JsonObject result = call("Runtime.evaluate", params, worker);
        JsonElement exception = result.get("exceptionDetails");
        if (exception != null) {
            return "exception: " + exception;
        }
        JsonElement value = result.getAsJsonObject("result").get("value");
        return value == null ? "undefined" : value.toString();
    }

    /** A full collection in a worker, then the bytes its JavaScript heap holds. */
    double collectAndMeasure(String worker) throws IOException, InterruptedException {
        call("HeapProfiler.collectGarbage", new JsonObject(), worker);
        return call("Runtime.getHeapUsage", new JsonObject(), worker)
                .get("usedSize")
                .getAsDouble();
    }

    /** A PNG of what the page shows. */
    byte[] screenshot() throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("format", "png");
        return Base64.getDecoder()
                .decode(call("Page.captureScreenshot", params).get("data").getAsString());
    }

    /** The page's viewport in CSS pixels. */
    double[] viewport() throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("expression", "[innerWidth, innerHeight]");
        params.addProperty("returnByValue", true);
        JsonArray size =
                call("Runtime.evaluate", params).getAsJsonObject("result").getAsJsonArray("value");
        return new double[] {size.get(0).getAsDouble(), size.get(1).getAsDouble()};
    }

    /** Runs {@code script} at the start of every page load from now on, and reloads the page. */
    void reloadWith(String script) throws IOException, InterruptedException {
        call("Page.enable", new JsonObject());
        JsonObject add = new JsonObject();
        add.addProperty("source", script);
        call("Page.addScriptToEvaluateOnNewDocument", add);
        call("Page.reload", new JsonObject());
    }

    /** Reloads the page, as the player's F5 or the browser's reload button would. */
    void reload() throws IOException, InterruptedException {
        call("Page.reload", new JsonObject());
    }

    /** Clears everything the browser keeps for {@code origin}: caches, service workers, storage. */
    void clearOrigin(String origin) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("origin", origin);
        params.addProperty("storageTypes", "all");
        call("Storage.clearDataForOrigin", params);
        call("Network.clearBrowserCache", new JsonObject());
    }

    /** Goes to {@code url} in the same tab. */
    void navigate(String url) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        call("Page.navigate", params);
    }

    /** The browser's product and version, {@code Chrome/151.0.7922.34} say. */
    String browserVersion() throws IOException, InterruptedException {
        return call("Browser.getVersion", new JsonObject(), null).get("product").getAsString();
    }

    /**
     * The page's runtime metrics ({@code Performance.getMetrics}): {@code JSHeapUsedSize},
     * {@code JSHeapTotalSize}, {@code TaskDuration} and the rest, by name.
     */
    Map<String, Double> metrics() throws IOException, InterruptedException {
        call("Performance.enable", new JsonObject());
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (JsonElement metric :
                call("Performance.getMetrics", new JsonObject()).getAsJsonArray("metrics")) {
            JsonObject entry = metric.getAsJsonObject();
            metrics.put(entry.get("name").getAsString(), entry.get("value").getAsDouble());
        }
        return metrics;
    }

    /**
     * Starts recording a trace of {@code categories} only (everything else excluded, so the trace
     * costs the page little), until {@link #stopTracing}.
     */
    void startTracing(List<String> categories) throws IOException, InterruptedException {
        traceEvents.clear();
        JsonObject config = new JsonObject();
        JsonArray included = new JsonArray();
        categories.forEach(included::add);
        config.add("includedCategories", included);
        JsonArray excluded = new JsonArray();
        excluded.add("*");
        config.add("excludedCategories", excluded);
        JsonObject params = new JsonObject();
        params.addProperty("transferMode", "ReportEvents");
        params.add("traceConfig", config);
        call("Tracing.start", params);
    }

    /** Stops the trace {@link #startTracing} started; its events. */
    List<JsonObject> stopTracing() throws IOException, InterruptedException {
        call("Tracing.end", new JsonObject());
        if (awaitEvent("Tracing.tracingComplete", 60) == null) {
            throw new IOException("The trace didn't finish");
        }
        List<JsonObject> collected = new ArrayList<>(traceEvents);
        traceEvents.clear();
        return collected;
    }

    /**
     * Clicks at ({@code x}, {@code y}), where the page opens a file chooser, and picks
     * {@code paths} in it as the player would.
     */
    void chooseFiles(double x, double y, List<String> paths) throws IOException, InterruptedException {
        call("Page.enable", new JsonObject());
        JsonObject intercept = new JsonObject();
        intercept.addProperty("enabled", true);
        call("Page.setInterceptFileChooserDialog", intercept);
        events.clear();
        try {
            click(x, y);
            JsonObject opened = awaitEvent("Page.fileChooserOpened", 10);
            if (opened == null) {
                throw new IOException("The page opened no file chooser");
            }
            JsonObject params = new JsonObject();
            JsonArray files = new JsonArray();
            paths.forEach(files::add);
            params.add("files", files);
            params.add("backendNodeId", opened.get("backendNodeId"));
            call("DOM.setFileInputFiles", params);
        } finally {
            intercept.addProperty("enabled", false);
            call("Page.setInterceptFileChooserDialog", intercept);
        }
    }

    /** Lets the page download files, into {@code directory} under the names it gives them. */
    void allowDownloads(String directory) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("behavior", "allow");
        params.addProperty("downloadPath", directory);
        params.addProperty("eventsEnabled", true);
        callBrowser("Browser.setDownloadBehavior", params);
    }

    /**
     * Waits up to {@code seconds} for a download whose name {@code name} accepts to finish.
     *
     * @return the file's name, or null if none finished in time
     */
    @Nullable
    String awaitDownload(Predicate<String> name, long seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Map<String, String> names = new HashMap<>();
        while (true) {
            long left = until - System.nanoTime();
            JsonObject event = left <= 0 ? null : events.poll(left, TimeUnit.NANOSECONDS);
            if (event == null) {
                return null;
            }
            JsonObject params = event.getAsJsonObject("params");
            switch (event.get("method").getAsString()) {
                case "Browser.downloadWillBegin" ->
                    names.put(
                            params.get("guid").getAsString(),
                            params.get("suggestedFilename").getAsString());
                case "Browser.downloadProgress" -> {
                    String file = names.get(params.get("guid").getAsString());
                    if (file != null && params.get("state").getAsString().equals("completed") && name.test(file)) {
                        return file;
                    }
                }
                default -> {
                    // Not a download's.
                }
            }
        }
    }

    private @Nullable JsonObject awaitEvent(String method, long seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long left = until - System.nanoTime();
            JsonObject event = left <= 0 ? null : events.poll(left, TimeUnit.NANOSECONDS);
            if (event == null) {
                return null;
            }
            if (event.get("method").getAsString().equals(method)) {
                return event.getAsJsonObject("params");
            }
        }
    }

    /** Evaluates {@code expression} in the page (waiting for it if it's a promise); its value as JSON. */
    String evaluate(String expression) throws IOException, InterruptedException {
        return evaluate(expression, true);
    }

    /**
     * Like {@link #evaluate(String)}, but {@code gesture} false evaluates without counting as a
     * player's click: the page's user activation, which lets it play sound, stays as it was.
     */
    String evaluate(String expression, boolean gesture) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);
        // As if the player had just clicked: fullscreen, Pointer Lock and the clipboard need it.
        params.addProperty("userGesture", gesture);
        JsonObject result = call("Runtime.evaluate", params);
        JsonElement exception = result.get("exceptionDetails");
        if (exception != null) {
            return "exception: " + exception;
        }
        JsonElement value = result.getAsJsonObject("result").get("value");
        return value == null ? "undefined" : value.toString();
    }

    /**
     * Slows the page's CPU by {@code rate} (Chrome's own throttling: 4 runs it a quarter as fast),
     * to stand in for a slower machine's CPU; 1 runs it at full speed.
     */
    void throttleCpu(double rate) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("rate", rate);
        call("Emulation.setCPUThrottlingRate", params);
    }

    /** A full garbage collection in the page, so a heap figure read next counts only what's live. */
    void collectGarbage() throws IOException, InterruptedException {
        call("HeapProfiler.collectGarbage", new JsonObject());
    }

    /**
     * Starts the sampling heap profiler: from now on it keeps a sample of every allocation, with
     * the stack that made it, until the object is collected (what holds the heap, and where
     * it was allocated). WebAssembly GC objects are sampled like JavaScript ones.
     */
    void startHeapSampling() throws IOException, InterruptedException {
        call("HeapProfiler.enable", new JsonObject());
        JsonObject params = new JsonObject();
        // Bytes between samples, on average: fine enough for the structures that matter.
        params.addProperty("samplingInterval", 16384);
        call("HeapProfiler.startSampling", params);
    }

    /**
     * The samples {@link #startHeapSampling} has taken of objects still alive, as Chrome's
     * {@code .heapprofile} JSON (DevTools' Memory panel opens it).
     */
    String heapSamples() throws IOException, InterruptedException {
        return call("HeapProfiler.getSamplingProfile", new JsonObject())
                .get("profile")
                .toString();
    }

    /**
     * Writes a heap snapshot of the page to {@code file} (Chrome's {@code .heapsnapshot}, which
     * DevTools' Memory panel opens; {@link HeapSnapshotSummary} sums it by type). Streamed: a
     * gigabyte heap's snapshot is several gigabytes of JSON.
     */
    void heapSnapshot(Path file) throws IOException, InterruptedException {
        heapSnapshot(file, session);
    }

    /** {@link #heapSnapshot(Path)} of a worker's session (or the page's, if null). */
    void heapSnapshot(Path file, @Nullable String target) throws IOException, InterruptedException {
        call("HeapProfiler.enable", new JsonObject(), target);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            snapshotSink = out;
            try {
                JsonObject params = new JsonObject();
                params.addProperty("reportProgress", false);
                call("HeapProfiler.takeHeapSnapshot", params, target, TimeUnit.MINUTES.toSeconds(15));
            } finally {
                snapshotSink = null;
            }
        }
    }

    /** Starts sampling the page's JavaScript and WebAssembly (the DevTools protocol's profiler). */
    void startProfile() throws IOException, InterruptedException {
        startProfile(session);
    }

    /** {@link #startProfile()} in a worker's session (or the page's, if null). */
    void startProfile(@Nullable String target) throws IOException, InterruptedException {
        call("Profiler.enable", new JsonObject(), target);
        JsonObject interval = new JsonObject();
        interval.addProperty("interval", 200);
        call("Profiler.setSamplingInterval", interval, target);
        call("Profiler.start", new JsonObject(), target);
    }

    /** Stops the profiler {@link #startProfile} started; the profile, as Chrome's {@code .cpuprofile} JSON. */
    String stopProfile() throws IOException, InterruptedException {
        return stopProfile(session);
    }

    /** {@link #stopProfile()} in a worker's session (or the page's, if null). */
    String stopProfile(@Nullable String target) throws IOException, InterruptedException {
        return call("Profiler.stop", new JsonObject(), target).get("profile").toString();
    }

    /** Moves the mouse to ({@code x}, {@code y}) in CSS pixels and clicks the left button there. */
    void click(double x, double y) throws IOException, InterruptedException {
        mouse("mouseMoved", x, y, 0);
        mouse("mousePressed", x, y, 1);
        mouse("mouseReleased", x, y, 1);
    }

    /**
     * Presses the left button at ({@code x1}, {@code y1}), moves to ({@code x2}, {@code y2}) in
     * {@code steps} steps {@code stepMillis} apart with it held, and lets go there: a drag.
     */
    void drag(double x1, double y1, double x2, double y2, int steps, long stepMillis)
            throws IOException, InterruptedException {
        mouse("mouseMoved", x1, y1, 0);
        mouse("mousePressed", x1, y1, 1);
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMillis);
            mouse("mouseMoved", x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, 0, 1);
        }
        Thread.sleep(stepMillis);
        mouse("mouseReleased", x2, y2, 1);
    }

    /** Moves the mouse without clicking, as a player's hover. */
    void move(double x, double y) throws IOException, InterruptedException {
        mouse("mouseMoved", x, y, 0);
    }

    private void mouse(String type, double x, double y, int clickCount) throws IOException, InterruptedException {
        mouse(type, x, y, clickCount, type.equals("mousePressed") ? 1 : 0);
    }

    /** @param buttons the buttons held, as a bit mask (1 the left) */
    private void mouse(String type, double x, double y, int clickCount, int buttons)
            throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("button", clickCount == 0 && buttons == 0 ? "none" : "left");
        params.addProperty("buttons", buttons);
        params.addProperty("clickCount", clickCount);
        call("Input.dispatchMouseEvent", params);
    }

    /** Types {@code text} as key presses, one character at a time. */
    void type(String text) throws IOException, InterruptedException {
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            JsonObject down = new JsonObject();
            down.addProperty("type", "keyDown");
            down.addProperty("text", character);
            down.addProperty("key", character);
            call("Input.dispatchKeyEvent", down);
            JsonObject up = new JsonObject();
            up.addProperty("type", "keyUp");
            up.addProperty("key", character);
            call("Input.dispatchKeyEvent", up);
        }
    }

    /**
     * Presses or releases one key, by its {@code KeyboardEvent.code}, as a keyboard with a US
     * layout would send it.
     *
     * @param modifiers the modifiers held, as the protocol's bits ({@link Keys#ALT} and so on)
     */
    void key(String code, boolean down, int modifiers) throws IOException, InterruptedException {
        Keys.Key key = Keys.of(code, (modifiers & Keys.SHIFT) != 0);
        JsonObject params = new JsonObject();
        // A shortcut types nothing, so its press is a "raw" one, without text.
        boolean types = down && !key.text().isEmpty() && (modifiers & (Keys.CTRL | Keys.ALT | Keys.META)) == 0;
        params.addProperty("type", down ? (types ? "keyDown" : "rawKeyDown") : "keyUp");
        params.addProperty("code", code);
        params.addProperty("key", key.key());
        params.addProperty("windowsVirtualKeyCode", key.virtualKeyCode());
        params.addProperty("nativeVirtualKeyCode", key.virtualKeyCode());
        params.addProperty("modifiers", modifiers);
        if (key.location() != 0) {
            params.addProperty("location", key.location());
        }
        if (types) {
            params.addProperty("text", key.text());
            params.addProperty("unmodifiedText", key.text());
        }
        call("Input.dispatchKeyEvent", params);
    }

    /** Commits text as an input method would, with no key events. */
    void insertText(String text) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        call("Input.insertText", params);
    }

    /** Shows an input method's uncommitted composition, with the cursor at its end. */
    void compose(String text) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        params.addProperty("selectionStart", text.length());
        params.addProperty("selectionEnd", text.length());
        call("Input.imeSetComposition", params);
    }

    /**
     * Makes the page's viewport {@code width} by {@code height} CSS pixels at {@code density}
     * device pixels each: a window resize, or a move to a denser display.
     */
    void resize(int width, int height, double density) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("width", width);
        params.addProperty("height", height);
        params.addProperty("deviceScaleFactor", density);
        params.addProperty("mobile", false);
        call("Emulation.setDeviceMetricsOverride", params);
    }

    /** Lets the page read and write the clipboard without asking, as a player who allowed it. */
    void grantClipboard(String origin) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        JsonArray permissions = new JsonArray();
        permissions.add("clipboardReadWrite");
        permissions.add("clipboardSanitizedWrite");
        params.add("permissions", permissions);
        params.addProperty("origin", origin);
        callBrowser("Browser.grantPermissions", params);
    }

    /** Drags files from the machine onto the page at ({@code x}, {@code y}) and drops them. */
    void dropFiles(double x, double y, List<String> paths) throws IOException, InterruptedException {
        JsonObject data = new JsonObject();
        data.add("items", new JsonArray());
        JsonArray files = new JsonArray();
        paths.forEach(files::add);
        data.add("files", files);
        // Copy.
        data.addProperty("dragOperationsMask", 1);
        for (String type : new String[] {"dragEnter", "dragOver", "drop"}) {
            JsonObject params = new JsonObject();
            params.addProperty("type", type);
            params.addProperty("x", x);
            params.addProperty("y", y);
            params.add("data", data);
            call("Input.dispatchDragEvent", params);
        }
    }

    private JsonObject call(String method, JsonObject params) throws IOException, InterruptedException {
        return call(method, params, session);
    }

    /** A command for the browser itself rather than the page. */
    private JsonObject callBrowser(String method, JsonObject params) throws IOException, InterruptedException {
        return call(method, params, null);
    }

    private JsonObject call(String method, JsonObject params, @Nullable String target)
            throws IOException, InterruptedException {
        return call(method, params, target, TIMEOUT_SECONDS);
    }

    private JsonObject call(String method, JsonObject params, @Nullable String target, long timeoutSeconds)
            throws IOException, InterruptedException {
        int id = ids.incrementAndGet();
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        if (target != null) {
            message.addProperty("sessionId", target);
        }
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        pending.put(id, reply);
        socket.sendText(message.toString(), true).join();
        JsonObject response;
        try {
            response = reply.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("DevTools didn't answer " + method, e);
        } finally {
            pending.remove(id);
        }
        JsonElement error = response.get("error");
        if (error != null) {
            throw new IOException(method + " failed: " + error);
        }
        return response.getAsJsonObject("result");
    }

    private void received(String text) {
        JsonObject message = JsonParser.parseString(text).getAsJsonObject();
        JsonElement id = message.get("id");
        if (id != null) {
            CompletableFuture<JsonObject> reply = pending.get(id.getAsInt());
            if (reply != null) {
                reply.complete(message);
            }
        } else if (message.has("method")) {
            String method = message.get("method").getAsString();
            // Only what steps wait for, and the console when it's collected over the protocol.
            Writer sink = snapshotSink;
            if (method.equals("Target.attachedToTarget")) {
                JsonObject params = message.getAsJsonObject("params");
                JsonObject info = params.getAsJsonObject("targetInfo");
                String session = params.get("sessionId").getAsString();
                workers.put(
                        session,
                        info.get("title").getAsString() + " " + info.get("url").getAsString());
                workerOrder.add(session);
            } else if (method.equals("Target.detachedFromTarget")) {
                String session =
                        message.getAsJsonObject("params").get("sessionId").getAsString();
                workers.remove(session);
                workerOrder.remove(session);
            } else if (method.equals("Runtime.consoleAPICalled")) {
                console.add(consoleLine(message.getAsJsonObject("params")));
            } else if (method.equals("HeapProfiler.addHeapSnapshotChunk") && sink != null) {
                try {
                    sink.write(message.getAsJsonObject("params").get("chunk").getAsString());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (method.equals("Tracing.dataCollected")) {
                for (JsonElement event : message.getAsJsonObject("params").getAsJsonArray("value")) {
                    traceEvents.add(event.getAsJsonObject());
                }
            } else if (method.startsWith("Browser.download")
                    || method.equals("Page.fileChooserOpened")
                    || method.equals("Tracing.tracingComplete")) {
                events.add(message);
            }
        }
    }

    /** A console call as one line: its level, then its arguments as the console prints them. */
    private static String consoleLine(JsonObject params) {
        StringBuilder line =
                new StringBuilder("[").append(params.get("type").getAsString()).append("]");
        for (JsonElement argument : params.getAsJsonArray("args")) {
            JsonObject value = argument.getAsJsonObject();
            line.append(' ');
            if (value.has("value")) {
                JsonElement primitive = value.get("value");
                line.append(primitive.isJsonPrimitive() ? primitive.getAsString() : primitive.toString());
            } else if (value.has("description")) {
                line.append(value.get("description").getAsString());
            } else {
                line.append(value.get("type").getAsString());
            }
        }
        return line.toString();
    }

    @Override
    public void close() {
        String tab = openedTab;
        if (tab != null) {
            // A browser this tool didn't start keeps running: the tab goes, or every run's game
            // would stay open in it, holding its memory.
            openedTab = null;
            try {
                JsonObject target = new JsonObject();
                target.addProperty("targetId", tab);
                callBrowser("Target.closeTarget", target);
            } catch (IOException | RuntimeException e) {
                System.err.println("Couldn't close the tab: " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        socket.abort();
    }

    /** Collects each message's fragments and hands whole messages on. */
    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        volatile @Nullable DevTools devTools;

        @Override
        public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                DevTools target = devTools;
                if (target != null) {
                    target.received(text);
                }
            }
            webSocket.request(1);
            // Null: the data may be reused at once, which suits a copy into the builder.
            return null;
        }
    }
}
