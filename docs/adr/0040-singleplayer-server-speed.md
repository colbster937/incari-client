# ADR 0040: Singleplayer's server, faster: wakeups, math, spawn settings, stronghold rings

- Status: accepted
- Date: 2026-10-07

## Context

With the server in a worker (ADR 0039), a new world took 29 s to create on the desktop and 62 s on
`laptop-floor`; the idle tick at spawn was 9.7 ms (22 on the laptop), and the laptop's fly-through
drew ~20 sections because terrain couldn't keep up.

We surveyed the server-side optimization mods (Lithium, C2ME, Noisium, ScalableLux, ModernFix, Chunky)
against profiles of the worker itself (`-Pbenchmark.profile` now records them). Nothing here is a mod's
code. The profiles found:

| Where | What |
| --- | --- |
| Creating a world | 18 of 25 s with the worker 99% idle while picking spawn |
| Everywhere | 8–18% of busy time calling out to JavaScript |
| Idle spawn area | 55% of each tick in `NaturalSpawner.createState`, spawning off |
| Opening a world | half the time searching biomes for 128 stronghold rings |
| Fly-through | noise 50%, surface 24%, features 10% (not addressed here) |

## Decision

- **Wakeups without the timer clamp** (`wakeups.ts`): picking spawn makes ~8,000 hand-offs between
  the world-gen task and the IO thread. TeaVM schedules every wakeup with `setTimeout(0)`, which
  browsers clamp to 4 ms once nested five deep: 16–22 s of idle waiting. In the worker, events due
  now drain from a `MessageChannel` instead. The page keeps timers unless `?wakeups=channel`
  (ADR 0043 found it unsafe there).
- **`Math.floor`, `ceil`, `sqrt` as wasm instructions** (`MathIntrinsics`): TeaVM imported them from
  JavaScript, and noise floors every coordinate. All three are exact in IEEE 754 (`MathProbe`). Calls
  out fell from 18% to 1% of the worker's time.
- **Spawn settings once per biome** (`SpawnCosts`) [`?spawncosts=game`, `?spawncosts=verify`]: the
  mob cap asked for spawn settings at every counted mob every tick; they depend only on the biome
  unless a timeline changes them. Verified over 580,000 lookups: no difference.
- **Stronghold rings searched when needed** (`StrongholdRings`) [`?rings=game`]: the game started all
  128 biome searches as the overworld loaded, ahead of the spawn area on a one-thread worker. Now
  positions and random sources are worked out up front in the game's order, and each search runs the
  first time a chunk within reach needs it; the rest run in the background after a minute.
  `StrongholdRingsTest` checks identical positions for two seeds.

## Results

Singleplayer benchmark, Fast release:

| | Desktop before | after | `laptop-floor` before | after |
| --- | ---: | ---: | ---: | ---: |
| Create world | 29.4 s | 7.5 s | 61.6 s | 32.5 s |
| Save and Quit | 5.7 s | 2.1 s | 10.7 s | 5.6 s |
| Reopen | 7.9 s | 5.6 s | 32.1 s | 22.0 s |
| Idle tick at spawn | 9.7 ms | 5.1 ms | 22.4 ms | 14.4 ms |
| Fly-through tick | 4.4 ms | 2.5 ms | 14.5 ms | 4.6 ms |
| Sections drawn, fly-through | | | 21 | 89 |

On the laptop the page now draws 3–4x more terrain, so its frame figures aren't comparable: on two
cores, handling arriving chunks is now the page's cost, beside world generation. Baselines re-recorded.

## Consequences

- The page keeps TeaVM's timers; `wakeups.js` is one more script the loader serves.
- World generation (noise, surface) is now the worker's cost. Compiling density functions at build
  time was the next idea; more generating workers would cost 260–420 MB each.
- Worker profiles are part of `-Pbenchmark.profile`.
- The desktop is unchanged.
