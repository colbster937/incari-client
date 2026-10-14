# ADR 0063: The modules' first performance pass

- Status: accepted
- Date: 2026-10-14

## Context

A new benchmark script, `features.txt`, plays the page's own world with every HUD and play module
on (`?modules=`, a development option) and with none, standing at spawn and flying east over new
terrain. With all on, the page spent about 1 ms more CPU a frame at 4x throttle, a third more than
without. The modules' own code was careful; the cost was under them:

- **The minimap redrew every drawn chunk on a timer**, changed or not: 2 to 3 chunks of 256 columns
  a tick, each with a texture upload and a world-map record.
- **Its refresh loop read the clock up to 729 times a tick.** In the browser each reading is a call
  out to the page.
- **The player's view was rebuilt on every call** (equipment, sorted effects, position), 30 times
  or more a refresh with a full HUD. Coordinates looked the biome up three times a refresh; Totems
  counted the inventory three times.
- **Saving the explored map deflated every changed region in one tick**, every 30 seconds.

## Decision

- **The minimap redraws a chunk only when the game marks it changed**, through one patch: a hook
  beside the particle light's in `LevelExtractor.setSectionDirty`, which every block change, light
  update and chunk load passes. Changed chunks near the player redraw at most every half second,
  the rest every second. A redraw that comes out the same isn't uploaded or recorded.
- **The minimap's tick reads the clock only after a redraw.**
- **The player's view refreshes once a frame and once a tick** (`ModuleGame.newTick`, and the
  frame's partial tick). The biome's name is kept until the player changes block; an item count
  until the next refresh.
- **The HUD canvas keeps the last 128 text widths** (by hash), forgotten when the font changes and
  every 64 draws.
- **The explored map saves one region a tick** when its half minute comes round.
- **The minimap's round mesh uses `Circles`' corner table**, not two trig calls a corner a frame.
- **The pixel-grid crosshair's outline is drawn as runs**, not four 1x1 fills a lit cell.

Nothing looks different: the benchmark's HUD and minimap screenshots match, and `smokeWaypoints`,
`smokeHudModules`, `smokeVisualModules` and `smokeFeatures` pass.

## Results

`features.txt`, desktop, CPU throttled 4x, Fast release, CPU a frame (tick + extract + render
submit). Mean of runs: before 2, after 3 with modules; 2 each without.

| Scenario | Modules | Before (ms) | After (ms) | Over none, before → after |
| --- | --- | ---: | ---: | ---: |
| Standing | none | 3.06 | 3.16 | |
| Standing | all | 4.02 | 3.65 | +0.96 → +0.49 |
| Flying | none | 4.45 | 4.44 | |
| Flying | all | 5.51 | 5.31 | +1.06 → +0.87 |

The tick's share is gone: 1.54 → 1.26 ms standing (1.28 with none), 1.42 → 1.23 flying (1.11). The
page is capped at 60 fps, so the saving is CPU a Chromebook doesn't have.

## Left for later

- **Drawing remains:** about 0.25 ms extract and 0.3 ms render submit a frame. `?drawcensus` counts
  9.3 GUI draws a frame with all modules against 1.3 with none, mostly the minimap's. It draws every
  frame, and its backdrop, terrain, rim, arrow, badges and letters stack into separate GUI layers;
  each pipeline change in a layer is a draw.
- `Circles` gives a circle a side every 0.8 pixels round; fewer would look the same.
