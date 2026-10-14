# ADR 0015: The background pool runs between frames, and the render thread's waits are counted

- Status: accepted
- Date: 2026-09-28

## Context

The web platform reported no background threads, so the game's worker pool ran every task inline,
usually on the render thread mid-frame. Terrain meshing resubmits itself until the backlog is empty,
so one `schedule` meshed everything before the frame went on: turning in fresh terrain gave 38 fps
with frames up to 950 ms.

This is also the audit of blocking calls: a wait on the render thread holds the frame for as long
as it lasts, and deadlocks if what it waits for can only run on the render thread.

## Decision

- **The platform may run the game's pool:** `PlatformThreads.backgroundExecutor(name)` (null on the
  desktop, which keeps `ForkJoinPool`). The existing `Util.makeExecutor` hook takes it.
- **In the page the pool runs between frames** (`IdleTimeExecutor`): tasks queue; one coroutine runs
  them in slots that start after the frame is drawn and end 2 ms before the next is due (at least
  4 ms). A started task runs to its end. A thread waiting on a queued task waits for the next slot,
  which comes even in a hidden tab (within a quarter second), so nothing deadlocks.
- **Render-thread waits are counted:** the frame monitor (`window.__incariFrames`) times each frame;
  if the animation-frame callback returns before the frame ends, the render thread waited. Every
  `Object.wait` is routed through `Waits`, which adds render-thread waits to the frame's total and
  prints the stack of any wait ≥ 50 ms.
- **The build lists every blocking call** (`blocking.txt`), classified in
  [`docs/threading.md`](../threading.md).
- **The gate:** `soak.steps`, 30 minutes on a server, fresh terrain every minute, turning full
  circle; fails on any frame with ≥ 50 ms of render-thread waiting.

## Results

- **Gate passed** (2026-09-29, release build): 108,244 frames at 60 fps; longest wait total 32 ms,
  no frame over 50 ms. Fixed on the way: slots that started during a short wait made it wait for a
  whole task, and the frame limiter's skipped frames were counted as waits.
- Arriving in fresh terrain holds 59 fps (worst frame 50 ms); turning there 59 fps (worst 33 ms).

## Consequences

- Background work gets only the time frames leave: with full frames, the pool gets 4 ms and terrain
  appears more slowly rather than frames dropping. Workers (ADR 0023) add throughput.
- Resource reloads run on the pool too, so the loading screen animates.
- The frame monitor and wait tracing stay on in every build (a timestamp per frame and per wait).
