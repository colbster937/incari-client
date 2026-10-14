# ADR 0056: The look's first performance pass

- Status: accepted
- Date: 2026-10-11

## Context

The look (ADR 0048) builds screens from rounded rectangles, icons and shapes, each a small texture.
Nobody had measured it. Four costs grew with the number of controls:

- **A shape was many render states:** a rounded rectangle was nine blits, each a state with its own
  pose copy, bounds, texture setup and lookup. A button was up to 26; the inventory over 800.
- **The GUI places each state by searching what's under it,** which grows with the square of the
  states, and an edge over its fill always starts a new layer.
- **A texture per corner size and icon,** so each change cost a draw call.
- **The skin matched sprite names with an `equals` chain** (up to 35 comparisons) before every sprite.

## Decision

- **One atlas for the look's images** (`UiAtlas`): corners, icons, shapes, and a white square for
  plain fills, in shared 512-pixel pages, so they batch.
- **One render state per shape** (`RoundRects.box`: shadow or glow, fill and edge as one object).
  Buttons, wells, tabs, toasts, panels, slot wells, chat field and HUD editor frames use it. Icons
  and shapes go straight in as plain blits. The title's 110 snowflakes are one state.
- **Two accessors on `GuiGraphicsExtractor`** (one patch): the current scissor and a submit, so
  states built outside it clip inside scrolled lists. `Circles` now clips too.
- **`FrostSkin.sprite` uses a map** from name to kind.
- **A GUI player skin is redrawn only when it changes** (`SkinPictures`, one patch): the title
  screen's player was re-rendered every frame. The title player's turn now settles instead of easing forever.
- **`Circles` uses a corner table;** menus stop building strings every frame.

Nothing looks different (checked with `smokeLook` and side-by-side crops).

## Results

`menus.txt` benchmark, desktop with CPU throttled 4x, Fast release, means per frame:

| Scenario | Extract (ms) | Render submit (ms) | Draws | GCs / 20 s |
| --- | ---: | ---: | ---: | ---: |
| HUD alone | 0.52 → 0.49 | 1.14 → 1.13 | 27 → 26 | 8 → 7 |
| Module menu, Potato | 2.87 → 1.02 | 2.60 → 1.69 | 56 → 44 | 300 → 184 |
| Pause menu, Potato | 1.29 → 0.62 | 1.85 → 1.48 | 51 → 44 | 226 → 117 |
| Inventory, Potato | 7.13 → 0.75 | 3.41 → 1.80 | 57 → 48 | 170 → 97 |
| Inventory, Normal | 7.12 → 0.71 | 3.58 → 1.96 | 68 → 54 | 170 → 112 |

The inventory's 7 ms was mostly the layer search over 800 blits. The page is capped at 60 fps, so
the saving is CPU a Chromebook doesn't have.

## Left for later

- The screenshot gallery decodes full screenshots on the render thread for thumbnails.
- The game's GUI sort boxes keys and hashes texture records on every comparison.
- The atlas never frees a page; a GUI scale change paints every size again.
