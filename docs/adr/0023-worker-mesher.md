# ADR 0023: Terrain meshed in Web Workers by Incari's own mesher

- Status: accepted
- Date: 2026-09-30

## Context

Meshing terrain (`SectionCompiler.compile`: models, culling, light, fluids, visibility, translucent
sorting) is the game's heaviest recurring job. In the page it runs between frames (ADR 0015), so
terrain gets only the time frames leave; the fly-through's 1% low was 31 fps because of it.

The plan: a small WebAssembly mesher in a worker pool, flat snapshots in a
`SharedArrayBuffer` (or transferred buffers), a compact vertex format, and sorting in the worker.
Gate: no meshing on the main thread, no chunk-load stutter. The catch: a worker can't touch game
objects, and the picture must not change.

## Decision

### An exact mesher of Incari's own

- **`:mesher`** has no dependencies. A `Snapshot` (block ids, light, tints, per-position shapes for a
  section and two blocks around it) plus `MeshTables` (every state and model) go into `SectionMesher`,
  which writes exactly what the game's compiler writes: culling and skip rules, smooth or flat
  lighting with the game's quirks, tints, emission, fluids, visibility, sorted translucency. Written
  fresh, not copied.
- **Tables are read from the game, never assumed** (`MeshTableExport`): flags, light, shapes, skip
  rules, fluid heights, plant offsets and every baked quad. How a model picks parts is discovered by
  running it with a recording random source. States that don't fit are marked unsupported (none in
  26.3). The mesher's copies of the game's hash, random and table trig are checked against the game's.
- **Checked byte for byte:** `-Dincari.mesher=verify` meshes every section both ways; `smokeMesher`
  covers ten overworld places, the Nether, the End and the debug world, with smooth lighting and
  cutout leaves on and off: 17,763 sections, none different.

### In the page

- **Compiled alone:** `mesher.wasm` (~120 KB, 123 classes), run in one worker per core but one, up to four.
- **Messages are bytes:** tables once (~11 MB), a ~25 KB snapshot per compile, centers per re-sort.
  Copied through a `SharedArrayBuffer` slot when cross-origin isolated, else a transferred
  `ArrayBuffer`. The answer goes straight into the section's buffer pack.
- **The game's dispatcher is unchanged:** hooks in compile and re-sort call `Meshing`. A mesh the
  render thread needs at once (a block the player changed) is meshed in place by the same mesher.
- **Several meshes in flight:** a pool task waiting for a worker steps aside so another can run
  (`IdleTimeExecutor.awaitOutside`). Buffer packs start at 64 KB, not 8.8 MB.
- **Snapshots never hold up a frame:** filling one takes 5–10 ms, so it calls
  `PlatformThreads.pauseIfLate()` per layer and resumes in the next slot.
- **Slots end before the next frame:** the slot deadline only learns from frame steps near one
  interval (skipped frames had pulled it toward 33 ms).
- **Compact vertices:** 16 bytes instead of 28 (position and light in four shorts, colour, UVs in two
  shorts exact for power-of-two atlases). `TerrainVertexFormat` and `CompactTerrainRewrite` decode
  them in the shaders. The desktop keeps the game's format.
- **Options:** `?mesher=inline` (on the page's thread), `?mesher=vanilla` (the game's compiler),
  `?mesher=verify`.

## Results

Fly-through, desktop, vs `?mesher=vanilla`:

| | Workers | Game's compiler |
| --- | ---: | ---: |
| 1% low | 38–41 fps | 31.9 fps |
| p99 frame | 21.6–24.5 ms | 28.3 ms |
| Mesh queue, mean | 6.2 | 9.8 |
| Linear memory peak | 565 MB | 657 MB |
| Slowest frames' pool work between frames | ~1 ms | 25.2 ms |

What's left in the slowest frames is the game's tick (chunk packets, light), as in combat, which
loads no terrain.

## Consequences

- Meshing leaves the main thread; only copying the snapshot and the finished mesh stays.
- The mesher follows the game: changes in a new version show up in `smokeMesher`, and the arithmetic
  check fails loudly.
- Building the tables takes ~2 s in the page per world or reload: joins take about a second longer
  (until ADR 0032 kept them).
- The desktop meshes with the game's compiler unless asked (`-Dincari.mesher=incari`).
