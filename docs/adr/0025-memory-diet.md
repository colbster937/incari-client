# ADR 0025: The memory diet

- Status: accepted
- Date: 2026-10-01

## Context

A 4 GB Chromebook gives a tab far less than the 1–2 GB the client uses on a desktop, and running
out kills the tab. Done when the benchmark's heap is under budget with no GC pause over 16 ms.

There was no heap budget yet, and the benchmark only reported peaks (which include uncollected
garbage). Measured first on the desktop: **1016 MB live JavaScript heap at the title screen**, ~1.1 GB
in a world, and 565 MB of linear memory. The desktop JVM holds 250 MB at the same title screen.

## Decision

### Measuring

- **Live figures:** after the title screen and each scenario, the benchmark forces a GC and reads the
  JavaScript heap (`heapLiveMB`) and linear memory in use (`nativeLiveMB`, counted by `Heap`).
- **What holds memory:** `-Pbenchmark.heapProfile`, `-Pbenchmark.heapSnapshot`, and
  `-Pbenchmark.desktopJfr`, which plays the scenarios on the desktop under Flight Recorder to see
  allocation sites (the browser's tools can't see inside WebAssembly).
- **Budgets** (`budgets.properties`): absolute limits; over budget fails regardless of baseline.
  Memory budgets apply to every machine.
- **Measure the release build**, which players get (a dev build also holds 92 MB of TeaVM's deobfuscator).

### Where memory went

| Change | What |
| --- | --- |
| Data fixer built only when needed (`incari.client.datafix`) | The game builds every schema since 2013 at start-up, plus a discarded development check. The page passes current data through a stand-in and builds the real fixer only for old data. Most of the title-screen heap, and 2.1 s of start-up |
| Terrain heaps sized by tier (`TerrainHeaps`) | The game reserves 128 MB vertex + 32 MB index heaps per layer and 98 MB of staging: 540 MB of GPU memory, mirrored in linear memory. Now 32/8 MB at Standard (16/4 at Potato), staging 16 MB; a full heap gets another, costing one more multi-draw |
| Draw-only buffers keep no copy (`WebGl2Buffer`) | Large, never-mapped vertex buffers write straight to WebGL. A read-back still works, with a warning |
| Block states share equal data (`BlockStateSharing`) | Occlusion face arrays and sturdy-face tables are interned (a few hundred kinds among 35,000 states). `BlockStateSharingTest` checks every state |
| Decoded sounds budgeted (`DecodedSoundBudget`) | 16 / 32 / 96 MB by tier; the oldest leave the cache and decode again when replayed |
| Atlases capped by tier (`AtlasBudget`) | 2048 / 4096 / GPU limit. An atlas that doesn't fit halves its sprites' resolution (alpha-weighted) until it does. Vanilla fits every tier |
| `DeviceTier` | One setting for all the budgets above and the particle cap. The page takes Standard (or `?tier=`); the desktop High |

Found on the way: TeaVM's unmodifiable collections scanned every element, making model loading
quadratic (fixed in `TCollections`).

### Allocations in hot paths

GC counts showed where (the lobby collected 8x a second) and desktop JFR showed what:

- **World text laid out every frame** → `TextLayouts` caches up to 2048 layouts keyed by content,
  style and position (not obfuscated text). `TextLayoutsTest`.
- **Five vectors per item quad** → `VertexMath` writes with two reused vectors, bit-identical (`VertexMathTest`).
- **`ChunkSectionLayer.values()` copied per section per frame** → one shared array.

The lobby's render thread allocated 37 GB over 40 s before, 23 GB after. What's left would mean
restructuring the game's renderer, and causes no long pauses.

## Results

Desktop, release build, 2026-10-01, live figures after a forced GC:

| | Before | After |
| --- | ---: | ---: |
| JavaScript heap, title screen | 1016 MB | 255–265 MB |
| JavaScript heap, in a world | ~1.1 GB | 337–356 MB |
| Linear memory, peak | 565 MB | 74 MB |
| Longest GC pause | 5.2–7.3 ms | 1.8–5.5 ms |

Every scenario still runs at ~60 fps and within budget. Screenshot diffs 14/14.

## Consequences

- Memory budgets are enforced: over 320 MB live at the title, 420 MB in a world, 128 MB of linear
  memory, or a 16 ms GC pause fails `:app-web:benchmark`.
- An old world or options file pays to build the data fixer when first read.
- A draw-only buffer read back logs a warning: a new caller to look at.
- The Chromebook's heap should match; its pauses and times wait for the device.
