# ADR 0030: Ideas from the Java optimization mods: draw lists, facing culling, sprite animation

- Status: accepted
- Date: 2026-10-02

## Context

The floor device has about a fifth of the desktop's CPU and a GPU 30–60x slower. We surveyed the
Java optimization mods (Sodium, Lithium, ImmediatelyFast, FerriteCore, ModernFix, EntityCulling,
MoreCulling, Exordium, Enhanced Block Entities) for ideas not yet taken. Their licenses
rule out their code, so everything here is written fresh from what each mod does.

Flight Recorder on the desktop showed the render thread spending 65% of the fly-through (21% of
combat) rebuilding the terrain draw list every frame, a third of that in hash lookups. And the
blocks atlas animates ~41 sprites a tick into 5 mip levels: ~67 draws a frame, where the whole
fly-through frame was 80.

## Decision

Six changes, each with a page option that restores the game's way. The first four draw exactly the
game's picture; the HUD texture and meshed chests differ slightly, which was accepted.

| Change | Idea from | Off switch | Exact? |
| --- | --- | --- | --- |
| Terrain draw list kept between frames | Sodium | `?drawlists=game` | Yes |
| Back-facing quad runs left out | Sodium | `?faces=all` | Yes |
| Only visible animated sprites redrawn | Sodium | `?animations=all` | Yes |
| No threading checks on one thread | Lithium | — | Yes |
| HUD kept in a texture between refreshes | Exordium | `?hud=live` | Rounds one 8-bit step; lags ≤ one refresh |
| Closed chests meshed into terrain | Enhanced Block Entities | `?chests=game` | Shading within one step; mipmapped; no break cracks |

- **Draw lists** (`SectionDraws`, `TerrainDrawLists`): each section mesh keeps its draws per layer,
  re-read only when `UberGpuBuffer` moves its allocation. The frame's list is written straight into
  two reused buffers, same groups and order as the game's, with no object per section. The desktop
  uses it too.
- **Facing culling** (`incari.mesher.Facings`): the mesher groups solid and cutout quads by the
  axis-aligned direction they face (others unassigned). The draw list skips runs the camera can't
  see the front of (with a 3-block margin) and merges neighbours. The pipelines cull back faces
  anyway, so the picture is identical. `smokeMesher` checks the grouping (18,120 sections, none differ).
- **Animated sprites** (`AnimatedSprites`): meshes record which animated sprites they use; anything
  else that draws one notes it. Every animation still ticks, but only sprites drawn this or last
  frame get new frames. A sprite coming back into view is caught up before terrain draws. When
  terrain's sprites are unknown, everything animates as in the game.
- **Threading checks:** `ThreadingDetector`'s lock and semaphore are skipped where
  `PlatformThreads.oneThread()` (the page): one thread can't race itself.
- **HUD texture** (`HudCache`): with no screen open, the camera overlays and crosshair stay live;
  everything above them is drawn into a texture every 50 ms (Standard) or 66 ms (Potato), never at
  High, and blended over each frame. It only kicks in while frames come faster than refreshes
  (otherwise it would only add a pass). Between refreshes, nothing above the split is extracted.
- **Chests** (`ChestBaking`): a chest closed now and last tick is posed by its own renderer and its
  quads are written into the section's cutout layer; the game no longer draws it as a block
  entity. Chest textures join the blocks atlas; directional entity lighting is baked into vertex
  colour. Opening re-meshes the section without it; it's meshed back 11 ticks after closing. Page only.

`?drawcensus` logs each pipeline's draws every 600 frames; that's how the animations' share was found.

## Results

Development desktop, release build, 2026-10-02. Draws a frame:

| | Off | On |
| --- | ---: | ---: |
| Fly-through | 80 | 18.5 |
| Combat | 84 | 17.8 |
| Lobby (CPU ÷4) | 238 | 31 |

- Unthrottled: render submit 0.88–0.90 ms vs 0.94–2.21 off; GPU 0.60 vs 0.72 ms.
- CPU throttled 4x: fly-through tick 2.9 vs 3.1 ms, GPU 0.50 vs 0.57; combat GPU 0.51 vs 0.77; lobby
  tick 13.9 vs 15.5. FPS within noise: WebGL calls are cheap on the desktop. The floor device,
  where each call and the GPU cost more, should gain most.
- HUD texture in the lobby: extract 2.1 vs 2.2 ms, draws 27 vs 29. At 17 fps (throttled) it steps
  aside as designed.
- Correctness: screenshot diffs 15/15 (HUD 0.9971, chest scene 0.9996), `smokeMesher` clean.

## Not done

- **Lithium's skip of ambient particles where no biome adds any:** knowing that needs the biome
  lookup it would save (done differently in ADR 0031).

## Consequences

- Five more vendor patches.
- A path that moves a terrain allocation without `UberGpuBuffer` would leave a stale draw.
- Mesh tables are encoding version 2.
- `?drawlists=game` also turns off animation culling.
- A chest lid that opens without its block event (none in 26.3) would stay drawn shut until re-meshed.
