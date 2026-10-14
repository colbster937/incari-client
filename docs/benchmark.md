# The benchmark and the profiler

Every performance change states what it did to these figures, on the same scenes. A change that
makes them worse fails. ADR 0021 has the decisions; this page is how to use the tools.

## Profiler overlay

F9 in game (or `?overlay`) shows, on every screen:

- frame rate and 1% low;
- CPU time per frame: **tick** (ticks, packets), **extract** (preparing the frame), **render**
  (every WebGL call), and **GPU** time where the browser offers timer queries;
- draw calls, terrain draws and sections, the mesh queue;
- JavaScript heap (Chrome; exact only with `--enable-precise-memory-info`), linear memory, GC;
- a frame-time graph with a 60 fps line.

Times come from zones the game already marks for its own profiler. It costs nothing unless shown
or asked for (`?profile`, or `-Dincari.profile=true` on the desktop). The page keeps the last
16384 frames and start-up marks in `window.__incariFrames`.

## Running it

```sh
./gradlew :app-web:checkDownloadSize -PdownloadSize.noBrotli -Pteavm.release   # optional: size in the report
./gradlew :app-web:benchmark -Pbenchmark.machine=desktop -Pteavm.release
```

Use `-Pteavm.release`: it's what players get and what baselines are recorded with.

The runner starts a vanilla server with a fixed-seed world (`app-web/benchmark/server.properties`),
then opens the page four times: **cold start** (cleared origin), **warm start** (reload),
**join** (render distance 8, no frame limit), **rejoin** (from a reload). Then it plays
`app-web/benchmark/benchmark.txt` from the rejoined page:

| Scenario | Does | Stresses |
| --- | --- | --- |
| `flythrough` | Flies east at 11 blocks/s over unseen terrain, 55 s | Loading, meshing and drawing terrain |
| `combat` | Iron golems against waves of raiders in an arena, 40 s | Entities, animation, particles, projectiles |
| `lobby` | A crowded lobby: 80 armored mannequins, holograms, signs, a sidebar, particle fountains, 40 s | Entity models, text, particles |

The camera rides an invisible armor stand the server moves, so every run sees the same frames.

### Figures

Per scenario: median FPS and 1% low, p99 frame time, mean tick/extract/render/GPU ms, draw calls,
terrain sections, mesh queue, peak and live heap and linear memory (`heapLiveMB`, `nativeLiveMB`
after a full GC), and GC pauses. `slow1.*` breaks down the slowest 1% of frames (never gated).
Then the four load times, the warm start's parts, the title screen's live memory, and the download
size.

Reports: `app-web/build/benchmark/report.md` and `report.json`. Each run is appended to
`~/.incari/benchmark-history.jsonl` for the trend charts (never committed).

### Budgets and baseline

- **`app-web/benchmark/budgets.properties`:** absolute limits. Over budget fails, whatever the
  baseline says. Memory budgets apply to every machine; time budgets are per machine.
- **`app-web/benchmark/baseline.properties`:** each machine's recorded figures, and how much worse
  each may get (a percentage *and* an absolute amount, so noise doesn't fail). Figures without a
  rule never fail.

Record a machine, or accept a deliberate change (say why in the PR):

```sh
./gradlew :app-web:benchmark -Pbenchmark.machine=desktop -Pteavm.release -Pbenchmark.updateBaseline
```

### Options

| Property | Does |
| --- | --- |
| `-Pbenchmark.machine=<name>` | Whose baseline to use (default: host name) |
| `-Pbenchmark.updateBaseline` | Record instead of compare |
| `-Pbenchmark.only=flythrough` | Only these scenarios |
| `-Pbenchmark.script=<file>` | Another script in `app-web/benchmark/` (`hudeditor.txt`, `menus.txt`, `features.txt`); give it its own `-Pbenchmark.history` |
| `-Pbenchmark.query=<options>` | Extra page options, to compare a change on and off |
| `-Pbenchmark.query=modules=<ids>` | Modules on from the start (`all`, or ids by commas): `features.txt` run with and without them |
| `-Pbenchmark.gpu=gl\|vulkan\|swiftshader` | GPU backend; SwiftShader only gives draw counts |
| `-Pbenchmark.headed` | A visible window |
| `-Pbenchmark.cpuThrottle=4` | Slow the page's CPU 4x (a Chromebook stand-in); record as e.g. `desktop-cpu4` |
| `-Pbenchmark.connect=<host:port>`, `-Pbenchmark.pageHost=<addr>` | Drive a Chrome on another device (below) |
| `-Pbenchmark.skipBuild` | Use the existing build |
| `-Pbenchmark.profile` | CPU profiles per scenario and warm start (figures don't count) |
| `-Pbenchmark.gcTrace` | V8's GC events per scenario |
| `-Pbenchmark.heapProfile`, `-Pbenchmark.heapSnapshot` | Heap sampling or snapshots at the title and after each scenario (`-Pteavm.names` names the types) |
| `-Pbenchmark.desktopJfr` | Play on the desktop under Flight Recorder, for allocation sites (no figures) |

Every run also writes each scenario's frames to `build/benchmark/<scenario>.frames.csv`.

## Singleplayer

```sh
./gradlew :app-web:benchmark -Pbenchmark.singleplayer -Pbenchmark.machine=desktop -Pteavm.release -Pteavm.fast
```

No server: the page creates the seeded world itself (`?world=&seed=`), plays 15 s, saves, quits,
reopens from a reload, then plays `singleplayer.txt` (commands typed in chat):

| Scenario | Does |
| --- | --- |
| `spawn` | Hovers over spawn, 30 s: the worker server ticking |
| `flythrough` | The same flight, with the page's own server generating terrain |
| `combat` | The arena's waves, summoned by chat: mob AI on the page's server |

Extra figures from the server worker: ticks per second, tick times (mean, p95, max), loaded chunks,
`workerHeapLiveMB`; loads `createMillis`, `saveQuitMillis`, `openMillis`, and `leftHeapLiveMB`.
Its baseline is `singleplayer-baseline.properties`; budgets are shared.

## Reference devices

| Machine | What | Status |
| --- | --- | --- |
| `chromebook-4gb` | The floor device: the 60 fps gate | Not measured: needs the device |
| `laptop-floor` | i5-8250U held to 2 cores at 2.8 GHz, Chromium in a 3 GB scope with zram (ADR 0031). Faster than the floor, so an upper bound | Recorded |
| `desktop` | Ryzen 5 9600X, RX 9060 XT (the development desktop and CI runner) | Recorded |
| `chromebook-mid`, `laptop-igpu` | Other reference machines, for choosing quality tiers | Not measured |

### Measuring a Chromebook (or any remote Chrome)

1. In developer mode, add `--remote-debugging-port=9222` to `/etc/chrome_dev.conf`, run
   `sudo restart ui`, and tunnel to it: `ssh -N -L 9222:127.0.0.1:9222 root@<device>`.
2. Let the page count as secure: `chrome://flags` → **Insecure origins treated as secure** →
   `http://<desktop address>:18313`. Or reverse-tunnel the page too
   (`-R 18312:127.0.0.1:18312 -R 18313:127.0.0.1:18313`) and use `pageHost=127.0.0.1`.
3. Run:

   ```sh
   ./gradlew :app-web:benchmark -Pbenchmark.machine=chromebook-4gb -Pteavm.release \
       -Pbenchmark.connect=127.0.0.1:9222 -Pbenchmark.pageHost=<desktop address>
   ```

Private networks only: the page is a build with Mojang's code. After a failed run, check
`http://127.0.0.1:9222/json/list` for a leftover tab still running a game.

## CI's `perf` job

Runs the benchmark on a self-hosted runner labelled `incari-perf` and posts the report in the job
summary; a regression fails the job. Off until set up:

1. Register a runner with the label `incari-perf` (JDK 25, Node 24+, Chrome, a GPU). Nothing is uploaded.
2. Set repository variables `INCARI_PERF_RUNNER=true` and `INCARI_PERF_MACHINE` (default `desktop`).

It never runs for pull requests from forks, whose code would run on that machine. Keep it that way.
