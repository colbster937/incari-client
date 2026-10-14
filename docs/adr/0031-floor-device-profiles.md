# ADR 0031: What the floor stand-in's profiles found: items, ambient particles, glyphs, tints, indices

- Status: accepted
- Date: 2026-10-03

## Context

Until now every performance figure came from the desktop with its CPU throttled. With no Chromebook to
hand, an old laptop now stands in (`laptop-floor`: i5-8250U held to 2 cores at 2.8 GHz, UHD 620,
Chromium capped at 3 GB with zram; [`docs/benchmark.md`](../benchmark.md)). It's closer to the floor
device than anything before, but still faster.

Before this ADR it held 60 fps in the fly-through and combat (1% lows 11–26 fps) and 21 fps in the
lobby (tick 10, extract 9, render submit 26, GPU 18 ms). Profiles of a release build with Java names
found, in the lobby: items written vertex by vertex 15%, glyph vertices 13%, ambient particle
sampling 10%. In the fly-through: section snapshots 15% (half biome lookups for tints), rebasing
translucent indices 7.5%. The slowest frames were catch-up ticks spent on ambient particles.

## Decision

Eight exact changes (the game's picture, byte-identical where vertices), each but the index copy
with an off switch, plus three tools.

| Change | What | Off switch |
| --- | --- | --- |
| Generated items instanced | ADR 0027 missed flat items: every layer's quads carry a tint index. Now an item whose quads share one tint index is instanced with that layer's colour (`ModelBatches`) | `?items=game` |
| Ambient particles per biome a tick | Of the attribute's layers, only the biome's reads the position, so the value is kept per biome per tick (`AmbientParticles`). Biomes are still looked up per position | `?ambient=game` |
| A glyph quad in one call | `GlyphQuads` writes a glyph's four vertices straight into place for the two world-text formats, same FMA order, same bytes (`GlyphQuadsTest`) | `?glyphs=game` |
| Blank sign lines skipped | `SignLines`: a blank line draws nothing anyway | `?signs=game` |
| Tint biomes kept by position | An 8192-slot table, dropped when the client's biomes may change | `?tint=game` |
| Direct index copies | `RebasedIndices` uses straight linear-memory loads/stores, ranges checked once | — |
| Occlusion graph rebuild paced | `GraphUpdates` checks the clock every 64 nodes and yields at the slot's end, so a 37–41 ms rebuild no longer overruns a frame | `?graph=game` |
| Particle light kept | `ParticleLight` keeps answers until a block or its light changes, or a chunk loads | `?particlelight=frame` |

**Tools:**

- **`-Pteavm.names`:** a release build that keeps Java names, so profiles read as the game's methods
  (about twice the download; profiling only).
- **`?gpucensus`:** each draw's GPU time by pipeline. A draw that switches render targets also
  absorbs earlier work, so read shares with care.
- **Benchmark against a remote browser** now gets the page's console, closes its tab, and prints the
  stack of any 50 ms render-thread wait.

## Results

`laptop-floor`, release build, 2026-10-03, three alternating runs each (medians):

| Lobby | Before | After |
| --- | ---: | ---: |
| Frame rate (fps) | 21.1 | 31.6 |
| 1% low (fps) | 7.5 | 9.8 |
| Tick / extract / render submit (ms) | 10.2 / 8.7 / 26.2 | 5.1 / 7.8 / 15.9 |
| GPU (ms) | 18.0 | 10.6 |

| Fly-through and combat | Before | After |
| --- | ---: | ---: |
| Fly-through tick (ms) | 2.8 | 1.7 |
| Fly-through slowest-1% tick (ms) | 43.4 | 16.6 |
| Fly-through 1% low (fps) | 13.7 | 15.9 |
| Combat slowest-1% tick (ms) | 19.8 | 14.0 |
| Combat 1% low (fps) | 24.0 | 26.8 |

Both hold 60 fps at the median either way. Memory and start-up don't move. Screenshot diffs pass
15/15 (SSIM ≥ 0.9938).

## Consequences

- Five more vendor patches; the glyph one is largest, since Incari code can't reach LWJGL's memory
  functions.
- Items with per-quad tint changes within one layer (none in 26.3) would need the game's way.
- Ambient particles assume the biome is the only positional layer, as in 26.3.
- Particle light trusts that every light change marks render sections dirty, as meshing does.
- The occlusion graph's full update can take several frames, as the game already allows.
- What's left on the stand-in: the lobby is CPU-bound (~30 ms of tick, extract and submit across
  entities, text, particles); the slowest fly-through and combat frames are scavenges and catch-up
  ticks.
