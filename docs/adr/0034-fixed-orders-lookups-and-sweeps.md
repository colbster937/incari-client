# ADR 0034: Fixed translucent orders, the class library's lookups, collision sweeps, and Binaryen

- Status: accepted
- Date: 2026-10-04

## Context

`laptop-floor` holds the fly-through and combat at 60 fps and the lobby near 33 fps. What's left is
thin CPU time and slow frames. Profiles of the stand-in named:

| Share of page time | |
| --- | ---: |
| Combat: dropped items' ticks, a third in block collision sweeps | 10% |
| Lobby: particles removed from an `ArrayDeque` element by element | 2% |
| Combat: `Set.contains` on the class library's `Set.copyOf` (tag checks) | 1.2% |
| Fly-through: `drawBuffers` on every framebuffer bind | 3.1% |
| Fly-through: a `NullPointerException` thrown when no buffer pack is free | 0.7% |
| Fly-through: section snapshots (tint biome lookups 2.5%) | 11% |

## Decision

| Change | What | Off switch |
| --- | --- | --- |
| Fixed translucent order (Sodium's static sorting) | When every translucent quad in a section lies flat across one axis (still water), one order is correct from any camera, because back faces are culled. The mesher writes that order (`FixedOrder`) and the game's re-sort is skipped. Page only | `?resort=game` |
| Air skipped in collision sweeps (Lithium) | `AirBlock` shapes are empty in every context, so skip them before asking (`Collisions`) | `?collisions=game` |
| Snapshots read section copies directly | `SnapshotBuilder` reads each copied section's palette, not 8,000 checked lookups. Desktop too, so `smokeMesher` checks it | `?snapshotreads=game` |
| Tint biomes dropped locally | A chunk loading or unloading drops cached biomes only in it and its neighbours (`TintBlend.chunkChanged`) | — |
| Hashed immutable sets and maps | `TTemplateCollections` probes an index table instead of comparing every element (`ImmutableCollectionsProbe`) | — |
| `ArrayDeque` middle removal | Shifts with `System.arraycopy` | — |
| Draw buffers set once | A framebuffer's draw buffers are set on its first draw bind (`Framebuffers`) | — |
| No throw for a busy dispatcher | Requeue without the `NullPointerException` the game threw and caught | — |

## Binaryen (`-Pteavm.wasmOpt`, opt-in at the time)

Runs `wasm-opt --closed-world -O3 -ocimfs 100` (Binaryen 133, pinned and fetched to
`work/cache/binaryen/`) after TeaVM. Adds ~13 minutes and 10 GB to a release build.

- **`--closed-world`** broke the page at first: functions JavaScript calls reached Binaryen only as
  function references, so it emptied `main`. `WasmGCJsoCommonGenerator` (teavm-patches) now exports them.
- **`-ocimfs 100`:** uncapped single-caller inlining made functions V8's optimizer ran out of memory on.
- Plain `-O3` without closed world measured nothing.

On `laptop-floor`: fly-through render submit 2.3 → 1.9 ms; the rest within noise; download about the
same. Kept opt-in until measured on the floor device (made the default in ADR 0043).

## Not done

- **Vertex pulling** (terrain without an index buffer): on the UHD 620 it cost up to two thirds more
  GPU time where vertices dominate.
- **Reusing entity render states:** constructors were 0.2% of combat, and reuse would trust every
  renderer to reset every field.

## Results

`laptop-floor`, release build, 2026-10-04. The three switchable changes, on vs off: combat tick 2.3
vs 2.7 ms; fly-through and lobby within noise (both already at their limits).

Profile shares before → after: `drawBuffers` 3.1% → 0; dispatcher stack traces 0.5% → 0; snapshots
11% → 8.3%; `Set.contains` 1.2% → 0.02%; `ArrayDeque` removal 2% → 0.

Found on the way: the lobby's mesh queue drains at ~1.5 sections a second when frames overrun,
because the background pool gets only 1 ms then (fixed in ADR 0035).

Correctness: screenshot diffs 15/15, `smokeMesher` (17,943 sections) clean, `SectionMesherTest`.

## Consequences

- Five more vendor patches.
- The fixed order trusts that terrain pipelines cull back faces.
- Skipping air trusts that air has no shape in any collision context, as in 26.3.
- Local tint drops trust that a biome lookup never reads past the neighbouring chunk.
