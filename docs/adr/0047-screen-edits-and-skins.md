# ADR 0047: The game's screens changed and redrawn without a patch each

- Status: accepted
- Date: 2026-10-10

## Context

The restyle reaches Incari's screens through `incari.client.ui` (ADR 0046), but players mostly see
the game's: title, pause, options. Each change to one had been its own patch; a restyle of a dozen
screens would add dozens of hooks in vendor code that moves every release. And it must change how
everything looks, not just where it sits.

## Decision

Five hooks; the rest in `incari.client.ui`.

**Changing what's on a screen:**

- **`ScreenEdits.edit(type, edit)`:** after each layout (`init`, `rebuildWidgets`, `resize`), edits
  get an `EditedScreen` to find widgets by their label's translation key and move, resize, relabel,
  remove, or add Incari's. Edits state where things go, so they're idempotent across resizes; what
  they added is removed before they run again.
- **`ScreenEdits.replace(type, replacement)`:** `Gui.setScreen` shows Incari's screen instead.

Edits match a screen's exact class. One that throws is logged and skipped. Nothing registered: a map check.

**Redrawing**, through one `GuiSkin` (`Skins.use`). Each method is asked first and returns whether
it drew, so a skin takes what it knows and the game draws the rest:

- **`widget`:** a whole widget (with `Skins.cursor`).
- **`sprite`, `spritePart`, `texture`:** any GUI sprite or texture by name, wherever drawn, HUD
  included. The name carries the state (`widget/button_highlighted`).
- **`background`:** the default backdrop.

While a skin draws, its own calls aren't skinned again. A skin that throws is dropped and the game's
look returns. No skin: a field read. With one: a call per sprite, so skins must not allocate.

## Limits

- Text is the game's font; a new font is a resource, not a hook.
- Screens that draw their own backdrop (title, pause, death, chat) skip `background`; their sprites
  still go through the skin.
- A screen that re-lays out without a rebuild or resize puts edited widgets back until its next layout.
- Widgets inside lists aren't the screen's children, so edits can't reach them (the skin can).
- A replaced screen's class is no longer what the game finds: check `instanceof` uses first.
- Existing per-screen patches stay where they add widgets inside layouts.

## Consequences

- The restyle is a `GuiSkin`, edits and replacements; Incari's screens follow with no change.
- `smokeScreens` checks edits (also after a resize), a replacement, a test skin, and a throwing skin dropped.
