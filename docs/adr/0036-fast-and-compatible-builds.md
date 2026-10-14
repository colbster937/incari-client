# ADR 0036: Fast and Compatible builds (JSPI instead of coroutines)

- Status: accepted
- Date: 2026-10-05

## Context

TeaVM compiles every method that might suspend as a coroutine, and its analysis spreads that through
shared library code (ADR 0027). In the release module, coroutines are 83,152 of 303,310 functions but
**81% of all instructions**, ~38% of which is the transformation's own bookkeeping. The fly-through
spends 87% of its WebAssembly time in coroutines. `CoroutineCostProbe`: the same code takes 8.3–8.7 ns
an item as coroutines vs 5.5–5.9 ns plain.

**JSPI** (WebAssembly JavaScript Promise Integration) lets the engine keep a suspended stack itself,
so nothing needs compiling differently. Chrome/Edge 137+ and Firefox 153+ have it; Safari doesn't yet.

The JavaScript fallback (ADR 0014) served only Chrome before 119 (end-of-life devices), ran far
slower, and was a second compile to maintain.

## Decision

- **Two WebAssembly GC builds, named for players:**
  - **Fast:** threads suspend through JSPI; nothing is a coroutine. Chrome/Edge 137+, Firefox 153+.
  - **Compatible:** TeaVM's coroutines, for browsers with WebAssembly GC but no JSPI (Safari, older
    Chrome and Firefox).

  The names say what a player weighs (speed vs reach) and are used everywhere: the loading screen,
  `?build=`, `-Pteavm.fast`, `build/teavm-fast/`, `./incari build --only`, the manifest.
- **The JavaScript fallback goes.** Browsers without WebAssembly GC are told to update.
- **TeaVM changes** (teavm-patches): with `incari.jspi` set, `AsyncMethodFinder` finds nothing async.
  `Fiber.Jspi` runs each fiber through `WebAssembly.promising` and suspends on a
  `WebAssembly.Suspending` import. `JspiTransformer` routes `Fiber.suspend`/`start` there.
- **The page:** `build.json` format 2 lists `fast` and `compatible`. Where both run, the loading
  screen asks (Fast recommended) and remembers.
- **Ending fibers on `pagehide`:** V8 never frees a suspended stack, so every reload leaked the
  whole game (50 MB a load in `ReloadLeakProbe`; the lobby held three old games, 1,140 vs 356 MB).
  `jspi.ts` resumes each waiting fiber with an order to end, and it unwinds by trapping, with no
  Java code run.
- **`./incari build`** compiles both; `--only` makes one.

## Results

`laptop-floor`, release builds:

| | Compatible | Fast |
| --- | ---: | ---: |
| Lobby median / 1% low | 32.5 / 7.6 fps | 60.4 / 32.0 fps |
| Lobby tick / extract / render submit | 7.8 / 7.9 / 14.8 ms | 1.6 / 3.8 / 9.6 ms |
| Combat 1% low | 27.0 fps | 38.9 fps |
| Cold start | 13.4 s | 10.2 s |
| Join | 19.5 s | 13.7 s |

Desktop: lobby render submit 4.05 → 2.38 ms, join 4.9 → 3.6 s, combat and lobby 1% low up 22–27%.
Module 64.1 → 36.5 MB (gzip 20.5 → 12.3 MB). GC pause spikes vary run to run in both builds.

`JspiProbe` (sleep, wait/notify, join, exceptions across waits, `currentThread`) passes in both;
`jspi.test.ts` runs the imports under Node's JSPI.

## Consequences

- Two release compiles per build.
- Code reached from a JavaScript callback still must not suspend (JSPI traps).
- In Fast, a woken thread resumes from a microtask, not inside the call that woke it. Nothing found
  relies on the old order.
- Thread switches through TeaVM's event queue cost ~4 ms in both builds (the `setTimeout` clamp; see
  ADR 0040).
- Compatible is unchanged, so running both separates Fast regressions from game ones.
- Drop Compatible once every browser that matters has JSPI (watch Safari).
