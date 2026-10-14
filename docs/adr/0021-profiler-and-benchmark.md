# ADR 0021: The profiler, the benchmark and the performance gate

- Status: accepted
- Date: 2026-09-29

## Context

The goal is 60 fps at render distance 8 on a 4 GB Chromebook. Every change must show what it
did, on the same scenes, and regressions must be caught before merge. That needs an
in-game overlay, a fixed benchmark world with scripted scenes, a runner for real Chromebooks, and a
CI report per pull request that fails on regressions.

## Decision

### Measuring a frame

- **The game's own profiler zones are the timers.** One hook (`Minecraft.run`) wraps the game's
  `ProfilerFiller` with Incari's `FrameProfiler` while collecting; it times the two outer zone levels
  and allocates nothing. Nothing collecting: the frame is untouched.
- **Split:** *tick*, *extract* (`update`, `extract`), *render* (handing the frame to the backend:
  every WebGL call), and *GPU*.
- **GPU time** from EXT_disjoint_timer_query_webgl2 (`GpuTimer`), a frame or two late; unknown (not
  zero) without the extension. The backend also counts draws, terrain draws and sections
  (`GpuFrameStats`). The desktop's Mojang backends report these as unknown.
- **`PlatformProfiling`:** memory (JVM heap on the desktop; JavaScript heap and linear memory in the
  browser), each frame, and start-up marks. The page keeps the last 16384 frames in
  `window.__incariFrames`.
- **1% low** is the frame rate over the slowest 1% of frames (their mean), so stutters show.

### The overlay

F9 (rebindable) or `?overlay`: FPS and 1% low, the frame split, draws, mesh queue, memory, GC, and a
frame-time graph. Text rebuilt 4x a second.

### The benchmark (`./gradlew :app-web:benchmark`)

- **Fixed world:** the screenshot harness's server, fixed seed, time and spawning stopped, every
  chunk pre-generated so scenes measure client loading.
- **Scripted scenes** (`benchmark.txt`): the player spectates an armor stand the server moves.
  `flythrough` (55 s east at 11 blocks/s over unseen terrain), `combat` (golems vs raiders, 40 s).
- **Loads:** cold start (cleared origin), warm start, join.
- **Figures** (`FrameStats`, `GcStats`): FPS, 1% low, p99 frame, tick/extract/render/GPU, draws,
  sections, mesh queue, memory, GC pauses from a DevTools trace. Chromium runs with precise memory info.
- **Gate** (`PerfBaseline`, `baseline.properties`): per machine, per figure, a direction and an
  allowed worsening (percentage *and* absolute). Past it fails. `-Pbenchmark.updateBaseline` records.
- **Report:** Markdown and JSON, plus history in `~/.incari/benchmark-history.jsonl` and trend charts.

### Real Chromebooks

The runner drives any Chrome over DevTools (`--connect`), serving the page from this machine
(`--page-host`). A Chromebook in developer mode exposes remote debugging through SSH
([`docs/benchmark.md`](../benchmark.md)). No Playwright: it uses the same protocol and can't drive a
stock Chromebook either, and `tools/webbuild` already has a DevTools client.

### Every pull request

Hosted runners can't compile the browser build (20 GB heap) and their software GPU gives meaningless
times, so CI's `perf` job runs on a self-hosted runner labelled `incari-perf` once
`INCARI_PERF_RUNNER` is set. It compiles, measures, and writes the report to the job summary; a
regression fails. Nothing is uploaded. Never for fork pull requests.

## Consequences

- Two three-line hooks (`Minecraft.run`, `Gui`); the rest is `incari.client.profiler` and
  `incari.platform.api.profile`.
- Figures are per machine. Until a Chromebook is recorded, the gate protects the desktop's figures:
  it catches regressions in work done, not the floor device's margins.
- On SwiftShader GPU time is unknown, but draw counts still gate.
- Players pay nothing until they press F9.
- The CI job waits for someone to register the runner.
