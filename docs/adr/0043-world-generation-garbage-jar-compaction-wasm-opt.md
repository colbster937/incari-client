# ADR 0043: Less garbage in world generation, the client jar compacted, faster saves, and wasm-opt and channel wakeups measured again

- Status: accepted
- Date: 2026-10-09

## Context

Left after ADR 0042: singleplayer's server worker is saturated on `laptop-floor`; memory headroom is
thin (a 4 GB Chromebook gives the tab ~3 GB; page and worker hold 350–400 MB of heap each, the
renderer 2 GB+); and small leads (channel wakeups, wasm-opt, cold start).

Heap snapshots showed ~50 MB in every copy of the game is the program itself (vtables, wrappers,
type data). The page also held 27 MB of Unicode font (271,000 glyphs, three objects each), and both
page and workers kept the whole 40 MB client jar in a buffer.

## Decision

Nothing here is a mod's code. Bracketed options turn a change off.

### World generation: less garbage

A quarter of the terrain step's allocated bytes (`TerrainStepBench` on the JVM) were positional
randoms: three objects to draw one number. In WebAssembly GC each is a real allocation.

- **First draw without the random** (`PositionalRandoms`) [`?randoms=game`]: a Xoroshiro128++
  generator's first number is a function of its seeds, so compute it directly.
  `PositionalRandomsTest` checks 100,000 positions bit for bit.
- **Density buffers reused** (`SurfaceBuffers`) [`?surfacebuffers=game`]: ore-vein rules filled four
  ~200 kB float arrays per chunk, too large for V8's young space. A thread now reuses them.
- **Chunks saved at zlib level 1** (`ChunkCompression`) [`?deflate=game`]: 2.7x faster than level 6,
  2.4 vs 2.0 kB a chunk; region sectors are 4 KiB, so files barely grow.

`TerrainEquivalenceTest` covers the first two.

### Memory

- **Client jar compacted** (`ZipArchive.compact`): only the entries a mount needs are copied out,
  still compressed. The page keeps 7.6 MB (assets and data packs); workers 4.4 MB (data packs,
  languages, root files) [`?workerjar=all`].
- **Unicode font packed** (`PackedUnihex`) [`?unihex=game`]: glyph rows in one byte array (~9 MB),
  rebuilt on request. `PackedUnihexTest` checks every glyph.

### Small leads

- **wasm-opt on by default for the Fast release build**: `-O3 --closed-world`, with inlining off
  (`-aimfs 0 -fimfs 0 -ocimfs 0 -pii 0`). Under a minute on Fast. ADR 0034's inlining made the
  fly-through's slowest frames slower; without it they aren't, and the module is 4.6 MB smaller.
  `-Pteavm.noWasmOpt` leaves it out.
- **Channel wakeups stay off on the page** (`?wakeups=channel`): 6% faster warm start, but in one of
  two singleplayer runs the page hung at spawn. The worker keeps them (ADR 0040).
- **`-Pbenchmark.heapSnapshot`** now snapshots the server worker too.

Looked at and left: smaller allocations (biome search 5%, aquifer 1.5%, ore randoms 1.3%); V8's lazy
compilation (turning it off didn't speed creation and slowed cold start by 2 s); the per-instance
program memory (the compiler's; closed world trims some); the 12 s cold start.

## Results

`laptop-floor`, Fast release, singleplayer benchmark, medians:

| | Before | After | After, changes off |
| --- | ---: | ---: | ---: |
| Create world | 27.7 s | 28.1 s | 27.8 s |
| Save and Quit | 4.0 s | 3.2 s | 3.7 s |
| Page heap live, spawn / fly-through | 384 / 367 MB | 334 / 318 MB | 352 / 335 MB |
| Peak renderer memory | 2389–2569 MB | 2265–2299 MB | 2275–2397 MB |
| Fly-through 1% low / combat 1% low | 16.4 / 34.2 | 20.4 / 39.3 | 18.0 / 23.2 |

The page holds ~50 MB less (jar ~32, font ~17). Creation time is within noise; the fly-through drew
12% more terrain.

wasm-opt, same build with and without:

| | Without | With |
| --- | ---: | ---: |
| Module raw / gzip | 38.5 / 12.4 MB | 33.6 / 11.2 MB |
| Warm start | 8.0 s | 7.5 s |
| Join | 16.0 s | 14.7 s |
| Singleplayer create / open | 28.1 / 21.4 s | 26.5 / 20.3 s |
| Server tick at spawn | 15.6 ms | 13.2 ms |

The fly-through's longest GC pause varies 11–42 ms across all builds; none holds the 16 ms budget
every run. `laptop-floor`'s baselines were re-recorded on this build (the multiplayer ones were still
Compatible's: lobby 32 vs 61 fps on Fast).

## Consequences

- The page holds ~60 MB less; each worker ~35 MB less outside its heap.
- Fast release builds need Binaryen (fetched to `work/cache/binaryen/`, Linux x86-64 only so far);
  elsewhere use `-Pteavm.noWasmOpt`.
- Browser-saved chunks take about a fifth more bytes inside the same sectors.
- The worker sees only the jar's data packs and languages (`?workerjar=all` to check if a server
  ever needs more).
