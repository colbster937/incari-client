# ADR 0042: Density functions' branches sampled where used, terrain helpers, and goals' flags as masks

- Status: accepted
- Date: 2026-10-08

## Context

On `laptop-floor` the singleplayer server worker is 99% busy in the fly-through, drawing 55 terrain
sections against the desktop's 860. Worker time: noise 28%, surface 20%, features 11%, biomes,
carvers and light ~10%, saving 5%. Idle at spawn, mobs are 28% (movement sweeps and goal selection).

Two things stood out in 26.3. A range choice sampled over a volume samples **both** branches over the
whole volume before picking per point (and `lerp` both sides): the overworld's caves sampled the whole
cave stack even in the sky. And the terrain step reads only the seed, nearby biomes and structure
pieces, so it could run off the server's thread.

## Decision

Nothing here is a mod's code. Bracketed options turn a change off.

- **Branches sampled only where used** (`DensityBranches`) [`?densitychoices=game`]: sample a choice's
  input first, then each branch only over the rows some point takes (likewise `lerp`'s sides).
  Values are exact: rows start at multiples of 48 blocks so interpolated cells line up, and caches
  are sampled no more than before. Only on cell-corner volumes (on single-block volumes it was slower).
- **Terrain step in helper workers** (`RemoteTerrain`) [`?terrainworkers=N`, **off by default**]: other
  copies of the game in child workers build chunks' terrain from a `TerrainJob` (biomes and nearby
  structure pieces); the server keeps two jobs per helper out and builds the rest. Off because it
  showed no gain (the desktop keeps up anyway; `laptop-floor`'s two cores are already full) and a
  helper costs 150–270 MB.
- **Goal flags as masks** (`GoalFlags`) [`?goalflags=game`]: each goal's control flags and the
  selector's disabled flags as bitmasks, answered as the game's loops would.

Looked at and left: `min`/`max` laziness (4% slower), per-column row ranges (no fewer rows), the
palette's last-index search (no gain), skipping same-block writes (0.3%), chunk-aware block sweeps
(≤ 2%), cheaper save compression (done in ADR 0043).

### A Chrome renderer crash, found and fixed

With a helper, Chrome's renderer died in every run's second session. The renderer process hit
Chrome's 16 GB sandbox limit in V8's compilation zone: TurboFan was compiling
`teavm@initReflection`, one 559 KB function TeaVM generates. Once enough instances of the module had
started (page, server, helper, over two sessions), V8 tiered it up and ran out of memory.
`ReflectionMetadataGenerator` (teavm-patches) now splits it into ~4000-instruction functions, and
`WasmGCModuleGenerator` splits the 5 MB start function the same way. With both, every run finished
at a 3.8–4.0 GB peak. Players' builds without helpers could have hit it too after opening enough worlds.

## Verified

- `TerrainEquivalenceTest` with lazy branches on and off: every block and heightmap identical.
- `RemoteTerrainTest`: 16 chunks built by a helper equal those built in place.
- `GoalFlagsTest`: masks answer as the game's loops for every flag combination.
- JVM terrain step: noise fill 5% faster, the whole step ~1.5%.

## Results

Singleplayer benchmark, Fast release, helpers off, changes off vs on:

| | Desktop off | on | `laptop-floor` off | on |
| --- | ---: | ---: | ---: | ---: |
| Create world | 5.9 s | 5.9 s | 27.2–27.4 s | 26.6–27.0 s |
| Server tick at spawn | 4.2 ms | 4.2–4.6 ms | 15.3–17.3 ms | 13.7–13.8 ms |
| Fly-through sections drawn | ~866 | ~866 | 78–90 | 88–108 |

On `laptop-floor` the idle tick fell from 15–17 to 14 ms (likely the goal flags); the rest is within
noise. The desktop keeps up either way.

## Consequences

- Terrain helpers stay available behind `?terrainworkers=N`; nothing starts one by default.
- A game worker unwinds its fibers before it closes, as the page does on `pagehide`.
- The desktop is unchanged (`worldgenServer` turns the lazy branches on with ADR 0041's changes).
