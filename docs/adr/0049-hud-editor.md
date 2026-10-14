# ADR 0049: The HUD editor

- Status: accepted
- Date: 2026-10-10

## Context

ADR 0046 gave every HUD element a placement (one of nine anchors, offsets, a scale) and left the
offsets for an editor. The player needs to see elements where they'll be in play, tell them apart
from the game's HUD, move and resize them, and reset them.

A blurred backdrop separates movable elements from everything else. But Potato exists because a
full-screen blur every frame is what a Chromebook can't spare (ADR 0048).

## Decision

### The editor

`HudEditorScreen` opens from **Edit HUD** beside the module menu's search, and from a module's
settings when it has HUD elements. It draws every enabled module's elements through the HUD's own
canvas, so what's shown is what play shows.

- **Frames:** each element on rounded glass, highlighted on hover, accent-edged when chosen, with a
  name tag, a resize handle and a bar showing its anchor side.
- **Moving:** drag snaps edges and middles to the screen's sides (2 units in), its middle, and other
  elements, with guide lines. Shift or the Snap toggle places freely. Arrows nudge (Shift: 10). Each
  move takes the **nearest anchor** (`Anchor.nearest`), so placement holds when the window or GUI
  scale changes.
- **Resizing:** scroll over an element, or drag its corner.
- **Undo and reset:** Ctrl+Z per action; Reset for the chosen element or all.
- **Right-click** opens the module's settings. With nothing to place, a card says so.
- **Samples:** elements with nothing to show draw a sample while editing (`HudCanvas.editing()`);
  ones a setting hides for good report `isPlaceable() == false`.

A glass toolbar at the top (title, Snap, Reset, Done) with a caption that explains or shows the
anchor and offsets mid-drag.

### The backdrop: blurred in both presets, cheap on Potato

Both presets blur the backdrop, lightly tinted. On **Potato**, `BackdropCache` redraws it **10
times a second** and copies it to a texture after the blur; frames in between skip the level and
HUD entirely and reuse the copy. Elements and toolbar draw every frame, so dragging stays smooth.
Normal blurs every frame. Three hooks: `GameRenderer.extract`, `GameRenderer.render` (don't age
pooled render targets on reuse frames), `GuiRenderer.draw`.

## Results

`laptop-floor`, Fast release, `hudeditor.txt`, 20 s at spawn:

| | HUD only | Pause menu | Editor, Potato | Editor, Normal |
| --- | ---: | ---: | ---: | ---: |
| 1% low (fps) | 32.8 | 12.5 | 36.6 | 50.2 |
| p99 frame (ms) | 26.8 | 21.4 | 20.5 | 19.1 |
| GPU (ms) | 6.4 | 7.2 | 2.3 | 8.1 |

All hold 60 fps at the median; the Potato editor's GPU cost is under a third of Normal's. A first
version recreated the render targets on each refresh (the pool aged them on skipped frames): fixed,
p99 34.2 → 20.5 ms.

## Consequences

- One patch (three hooks).
- `:framework` gains `Anchor.nearest`, `HudElement.bounds/place/resetPlacement/isPlaceable`,
  `HudCanvas.editing()`, and `ModuleSystem.drawHud` for one element.
- `smokeHudEditor` and `hudeditor.steps` (with a new `drag:` step) cover it.

## Not yet

- Grouping, multi-element alignment, a grid.
- Elements of disabled modules aren't shown.
