# ADR 0041: Singleplayer's files in place, a direct line to storage, and cheaper world generation

- Status: accepted
- Date: 2026-10-08

## Context

Profiles after ADR 0040 (desktop, Fast build with names):

| Where | Time went to |
| --- | --- |
| Fly-through, server worker (56% busy) | surface 27.5%, noise 22%, features 11%, saving 12% |
| Fly-through, page | passing the worker's file changes to storage: 10%, 4x the chunk packets themselves |
| Creating a world, worker | surface 28%, noise 21%, features 8%, worker start 13% |
| Idle at spawn | mob movement and collisions 43% of the tick; stronghold searches 33% |

Region files rewrite their 8 KiB header with every chunk saved, and a file kept only one changed
range between flushes, so each 250 ms flush copied most of every touched region file about seven
times, three on the page's thread. Both page and worker also held whole worlds in memory, which
grows with exploring: on a 4 GB Chromebook that heap runs out.

In 26.3 the material system writes ~16,000 blocks a chunk through `ProtoChunk.setBlockState`.
Density functions are already sampled over volumes, so compiling them would gain little.

## Decision

Nothing here is a mod's code. Bracketed options turn a change off.

- **World files left in place** [`?inplace=off`]: region files (`.mca`, `.mcc`) are never loaded
  whole when OPFS is the store and this tab holds it (`InPlaceFiles`). The page lists them by size
  and reads one only when it opens it. The server worker opens them with synchronous access handles
  (`OpfsFiles`), writing straight to the file. Hand-over sends sizes after a storage `sync`; hand-back
  releases handles and `stat`s the files. With IndexedDB or no sync handles, files travel with their
  bytes as in ADR 0039.
- **A direct line to storage:** the worker sends file changes straight to the storage worker through
  a port the page hands it (`BrowserStore.connect`), in the page's queue. A file keeps up to 8
  changed ranges (writes <512 bytes apart merge), so a region write sends its header and its chunk.
- **Packets a tick at a time** [`?packets=each`]: the page–worker link carries the game's stream,
  length-prefixed, one message per flush (`ChannelLink`). The server flushes once a tick.
- **Worker started early:** the world list and create screen start the worker, which bootstraps and
  builds the data fixer before a world is chosen (`WorldWorker.warmUp`).
- **Stronghold searches wait for quiet** while other background work is queued (`backgroundQueued`).
- **Surface writes** [`?surface=game`]: `SurfaceWrites` writes the material system's column as
  `ProtoChunk` would, but looks up heightmaps and build height once per chunk and skips the
  one-thread-safe palette check. Other chunk kinds go the game's way.
- **Biome cells** [`?biomecells=game|verify`]: when all eight biome cells around a 4×4×4 group agree,
  `BiomeCells` answers without the blur's hashing.
- **Build heights cached** [`?heights=game`]: `Heights` keeps each level's and chunk's bottom and
  height (they never change).
- **Criterion codecs built once** [`?criteria=game`]: DataFixerUpper names codecs eagerly; one per
  trigger saves 90–140 ms of worker start.

Looked at and left: collision sweeps (already direct; `Heights` fixed their cost), merging dropped
items (0.2%), the aquifer's search (~1%), the light engine (3%, spread thin), compiling density
functions (26.3 already samples volumes), TeaVM's byte copies (~0.6 ms/MiB; volume was the cost).

## Verified

- `TerrainEquivalenceTest` runs the real overworld terrain step on 4×4 chunks, 4 areas, 3 seeds,
  with and without the changes: every block and heightmap identical (a deliberately broken writer
  fails it).
- `SurfaceWritesTest`, `BiomeCellsTest`, `DataFileTest`, `JournalTest`, `InPlaceTest`,
  `ChannelLinkTest`, and the storage worker's tests.
- Whole worlds (`worldgenServer` + `tools/worldgen/compare.py`) can't be compared exactly: two runs
  with the changes off already differ in 108–147 of 1,764 chunks (feature order between chunks).
- `singleplayer.steps` passes in a worker and on the page.

## Results

Singleplayer benchmark against ADR 0040:

| | Desktop before | after | `laptop-floor` before | after |
| --- | ---: | ---: | ---: | ---: |
| Create world | 7.5 s | 5.9 s | 32.5 s | 27.1 s |
| Save and Quit | 2.1 s | 1.0 s | 5.6 s | 3.7–4.2 s |
| Reopen | 5.6 s | 5.3 s | 22.0 s | 20.8–21.7 s |
| Page heap peak | 725 MB | 599 MB | 760 MB | 506–532 MB |
| Longest GC pause, spawn / fly-through | 74 / 49 ms | 0.8 / 15 ms | | |
| 1% low, spawn / fly-through | 42 / 36 | 53 / 48 | 17 / 10 | 27–30 / 14–23 |

- World generation changes alone (`laptop-floor`): create 30.7 → 27.1 s, and the server keeps up
  with the camera longer (44 → 72–80 sections drawn in the fly-through).
- `?inplace=off` grows the worker heap 18–40 MB; `?packets=each` shows no measurable difference (kept
  for fewer wake-ups).

## Consequences

- Memory no longer grows with a world's size while it isn't open on the page.
- A world handed to the worker in place can't be renamed by it (the store has no rename for a file
  another program holds). The page renames, reading region files first.
- Opening a region file on the page suspends while it's read.
- The desktop is unchanged.
