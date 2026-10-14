# ADR 0032: Catch-up ticks paced, mesher tables kept, light maps updated in place, particles instanced

- Status: accepted
- Date: 2026-10-03

## Context

Still without a Chromebook, these changes were chosen as wins on any device, measured on
`laptop-floor`:

- **Catch-up ticks:** the slowest 1% of frames were mostly missed ticks run back to back after a stall.
- **Joining:** building the mesher's tables took ~7 s of the 22 s join, every time, for the same tables.
- **Fly-through GCs:** young collections averaged 10 ms (the lobby's 0.7 ms). Desktop JFR showed what
  lives long: 70 of 158 samples were the light engine copying its whole section map after each
  round of updates; 28 the occlusion graph cloning two sets.
- **Particles:** writing their vertices was 3.3% of the lobby.

## Decision

| Change | What | Off switch |
| --- | --- | --- |
| Catch-up ticks paced (`TickPacing`) | Owed ticks spread over frames: each frame runs the average needed (at least one), one more while over two frames' worth is owed. Debt capped at the game's own 10. Page only | `?ticks=game` |
| Mesher tables kept (`MeshTableCache`) | Encoded tables saved to `/incari/cache/mesher-tables.bin` in OPFS, keyed by build id, lighting settings, packs and every blocks-atlas sprite. Only with built-in packs and release builds | `?meshtables=build` |
| Light maps updated in place (`LightCopies`) | Instead of copying ~8,700 sections' arrays after each round, update the visible map only at sections noted as changed. One-thread only. Light from remote servers is kept as it arrives, not copied | `?light=game`, `?light=verify` |
| Occlusion graph copies refilled | Two kept sets refilled instead of cloned for each full update | `?graph=game` |
| Particles instanced (`ParticleInstancing`, `ParticleInstanceRewrite`) | Each particle's 12 floats and 2 ints go to the GPU as one instance; the vertex shader builds the quad from `gl_VertexID`. Translucent order kept. Only when every pipeline a layer may use has an instanced copy | `?particlequads=game` |

A first tick-pacing version allowed 20 ticks of debt and drained faster as it grew: combat's 1% low
fell after its stalls. Capped at 10, it doesn't.

The benchmark now joins twice (`load.rejoinMillis`), as a player's every later join.

## Results

`laptop-floor`, release build, 2026-10-03:

- **Tables:** built in 6.7 s, read back in 0.23 s. Rejoin 13.0–13.8 s vs 18.8–19.6 s.
- **Tick pacing**, fly-through (6 runs each): 1% low 26.9 vs 18.3 fps; slowest-1% frame 20.9 vs
  36.1 ms. Combat and lobby unchanged.
- **Light and graph copies:** young GCs 40 vs 44 and 347–369 vs 376–417 ms total; p99 frame 26.3 vs
  28.5 ms. `?light=verify` over 1,000 swaps: no difference.
- **Particles:** lobby render submit 15.1 vs 15.9 ms; 33.1 vs 31.6 fps.
- Screenshot diffs 15/15 (particles 0.9989, drawn instanced). `TickPacingTest`,
  `ParticleInstanceRewriteTest`.

## Not done

- **Chunk data off the GC heap:** JFR named the light copies, not chunk storage.
- **Decoding chunk packets off the main thread:** 2.4% of the page; too little to pay for a worker's copies.
- **Lithium's shape and collision caches:** collisions were 1–2%.
- **Found for later:** every packet handed to the game's thread costs a throw, ~10x dearer off the
  main thread (`PacketHandoffProbe`). ADR 0033 takes it.

## Consequences

- More vendor patches (tick hook, light swap and accessors, graph copies, four particle hooks).
- The kept tables cost 11 MB of heap; the title stays in budget.
- The tables' key must name every input; a new one (a model-changing setting) must join it.
- After a stall the game runs up to 10 ticks behind the clock for a few frames.
- In-place light trusts every change notes its section, as in 26.3; `?light=verify` checks.
- Particle positions are the GPU's arithmetic; the screenshot diffs check them.
