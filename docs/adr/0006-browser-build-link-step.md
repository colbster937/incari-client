# ADR 0006: How the browser build links: removals by rule, a class library for the rest

- Status: accepted
- Date: 2026-09-26

## Context

TeaVM compiles a closed world: everything reachable must exist at link time. As-is, 26.3 reaches
thousands of APIs a page can't have (LWJGL, JNA, OSHI, profilers, Realms, Vulkan, raw sockets) and JDK
classes TeaVM lacks. The TeaVM survey (`docs/port-backlog.md`) filed each as *stub*, *shim*, *rewrite* or *delete*
(`tools/survey/rules.txt`). The build must link and run the game loop with rendering off, without editing
libraries or scattering patches.

## Decision

- **The survey's rules decide removals.** `RemovalTransformer` rewrites every reference to a
  *missing* symbol filed under *delete* or *rewrite*. Existing symbols are never touched; a missing
  *stub* or *shim* stays a link error. `removed.txt` lists every removal and its rule.
- **Deleted features are stripped; rewrites throw.** A call into a deleted feature does nothing and
  its value is null or zero. A call into code still to be rewritten throws
  `UnsupportedOperationException`, so unported paths fail loudly. Class literals of removed classes
  become classes nothing is an instance of.
- **`web-classlib` provides stubs and shims**, written fresh from JDK contracts:
  - `substitutes.<pkg>.T<Name>` replaces `<pkg>.<Name>` (ours win over TeaVM's);
  - `additions` adds missing methods to TeaVM's classes;
  - SLF4J binds straight to a console logger;
  - LWJGL's memory classes over Java arrays (later linear memory, ADR 0008);
  - LWJGL's `STBImage` as a PNG decoder written for the build, pixel-checked against the JDK's;
  - the shader include cache's struct as plain memory.
- **`java.time` from ThreeTen Backport**, which TeaVM already maps.
- **Concurrency follows TeaVM:** threads are coroutines; locks, queues, executors and futures block
  through monitors and never spin. The worker pool ran inline (until ADR 0015).
- **Monitors that guard no suspension are dropped** (`MonitorElision`). Any monitor makes a method a
  coroutine, and that spreads through virtual calls: one `synchronized toString` made every
  `toString` a coroutine. In the page, code that can't suspend already runs exclusively, so monitors
  come off methods that don't wait or block; `lock()` there becomes a non-waiting acquire that fails
  loudly if held. The accepted risk: a method that blocks only through a virtual call loses a monitor
  it needed. `async.txt` lists hot methods still compiled as coroutines.
- **`ServiceLoader` finds nothing:** service lookups are only for extras the build leaves out.
- **TeaVM bugs are fixed in `tools/teavm-patches`**, each meant for upstream.
- **Generic superclasses kept only for type capture** (`TypeCaptures`): `TypeToken` subclasses read
  their type argument at run time.
- **Resources come from the client jar at run time** (since ADR 0011, mounted in place).
- **Entry-point hooks** for native library loading, the Vulkan check and the render step (off until
  ADR 0007). The loop yields to the page once per pass (`Platform.endFrame`).

## Consequences

- The build links without editing any library; every removal traces to a rule, and changing what
  the browser keeps is a reviewed change to `rules.txt`.
- A stripped feature can hide a null a later caller trips over. Deleted categories (profilers,
  telemetry, Realms, online-mode crypto) are chosen so that doesn't reach game logic; a crash traced
  to a stripped reference means a rule is in the wrong category.
- `web-classlib` is JDK surface we maintain, tested against the real JDK where possible.
