# ADR 0055: The built-in packs in the page, and Old Textures

- Status: accepted
- Date: 2026-10-11

## Context

The game's built-in packs, **Programmer Art** (pre-1.14 textures) and **High Contrast**, are zips in
the asset index, not the jar. The page never fetched them, so the Resource Packs screen listed
neither. Players asked for **Old Textures**, a module that switches Programmer Art and follows the
pack screen.

## Decision

- **Fetched at start** (`WebAssets`, 0.7 MB, before the game starts, since the repository lists them
  before the first reload) and **copied by `./incari build`**. Mojang's files on the builder's machine,
  as with sounds; nothing committed. A failed fetch leaves them out.
- **`Game.packSelected(id)` / `selectPack(id, on)`:** select as the Resource Packs screen does (at
  the pack's default place, under Incari's fixed pack), then save and reload. No patch.
- **`Module.followPack(id)`:** on turns the pack on, off turns it off; each tick the module's switch
  follows the game's pack, so the pack screen moves the module too. At start the game's choice wins
  (no extra reload).
- **Old Textures** (Visual): `followPack("programmer_art")`, no other settings.

## Consequences

- 0.7 MB more on a first visit.
- Toggling reloads resources, as the pack screen does: seconds on the floor device.
- `smokeFeatures`, `PackSwitchTest`, `OldTexturesTest` and `old-textures.steps` cover it.
