# ADR 0027: Performance follow-ups: the compile, the backend's calls, and the game's own costs

- Status: accepted
- Date: 2026-10-01

## Context

After ADRs 0021–0026 the desktop holds 60 fps everywhere, but a 4 GB Chromebook has about a fifth of its
single-thread speed and a GPU 30–60x slower. A review of profiles and code found costs nothing
had covered yet: in the compile, the WebGL2 backend's calls, and the game's own logic. Every change is
exact and has an off switch.

## Decision

| Area | Change | Off switch |
| --- | --- | --- |
| Compile | JOML's `HAS_Math_fma` is a constant `false` (as on the desktop), so `Math.fma` folds and inlines. It was the lobby's top self time (5.6%) | — |
| Compile | Fewer coroutines: untimed queue `offer`/`poll` no longer count as blocking; two always-complete `join()`s became `resultNow()`; a `forEach` became a loop | — |
| Backend | Uniform writes are uploaded once per buffer range before the next draw (`PendingUploads`) | `?uniforms=each` |
| Backend | Quad draws read the backend's quad indices from `vertexOffset / 4`, so attributes stay bound | `?rebase=off` |
| Backend | Re-sorted translucent indices are copied in place, not appended | — |
| Backend | Uploads use kept views of linear memory, not a new typed array each (`Staging`, `WebWorkerPool`) | — |
| Game | Translucent quads sorted by radix sort, same permutation as the game's stable sort (`QuadSort`) | `?sort=game` |
| Game | Snapshots read light a section at a time (`SnapshotLight`) | `-Dincari.mesher.lightByBlock` |
| Game | Biome tints reuse the row's previous samples, bit-exact (`TintBlend`, page only) | `?tint=game` |
| Instancing | Plain items drawn instanced (`ModelBatches.takeItem`) | `?items=game` |
| Instancing | Skins with all-or-nothing alpha and an opaque tint draw in the solid phase, so instancing takes them (`OpaqueSkins`, page only) | `?skins=game` |

Why some of these matter:

- **The upload views:** TeaVM wraps each JavaScript object that reaches Java in a `WeakMap`. Thousands
  of per-upload views piled up as ephemerons, and some GC pauses spent 33–36 ms marking them. Now
  ≤ 0.2 ms.
- **Opaque skins:** a texture with no partial alpha never blends, so drawing it before translucent
  models gives the same picture (only exactly equal depths could differ).
- **Coroutines:** the hot-method list didn't shrink. TeaVM's flow analysis merges everything through
  shared library code, so a blocking thread body makes every `Runnable.run` a coroutine. `async.txt`
  now opens with a per-package coroutine count. The real fix needs TeaVM changes (later: the Fast
  build, ADR 0036).

**Tools:** `-Pbenchmark.cpuThrottle=<n>` (a CPU stand-in for a Chromebook) and
`-Pbenchmark.gcTrace` (collector events per scenario).

## Not done

- **Skipping ambient-particle biome lookups outside the Nether:** exact and cut the tick 30–40%,
  but the page allocating less triggered V8's memory reducer: heap-shrinking pauses of 11–26 ms.
  Left out (ADR 0031 later did it differently).
- **The integrated server in a worker:** its own piece of work (later ADR 0039).

## Results

Development desktop, release build, 2026-10-01 (it holds 60 fps either way):

| | Before | After |
| --- | ---: | ---: |
| Lobby render submit (ms) | 9.9 | 8.4 |
| Lobby p99 frame (ms) | 22.9 | 21.7 |
| Fly-through longest GC pause (ms) | 5.5 | 3.5–8.8 |
| Fly-through live heap (MB) | 350 | 356 |

Items and skins instanced, lobby: render submit 8.4 → 6.5 ms, draws 132 → 97.

`async.txt`: 63% of reachable methods compile as coroutines. Correctness: screenshot diffs 14/14,
`smokeMesher` (18,060 sections) clean, `QuadSortTest`, `TintBlendTest`.

## Consequences

- Six more vendor patches.
- The coroutine count is worth guarding.
- `TintBlend` trusts that biome changes invalidate the game's tint caches (true in 26.3).
- A JavaScript object handed to Java on a hot path costs a weak-map entry: keep views, as
  `Staging`, `DrawRows` and `WebWorkerPool` do.
