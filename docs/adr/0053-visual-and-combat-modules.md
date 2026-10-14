# ADR 0053: The visual and combat modules: Cooldown, Crosshair, Freelook, Weather, Low Shield, 1.7

- Status: accepted
- Date: 2026-10-10

## Context

The second set the player asked for changes how the game looks: **Cooldown Indicator**,
**Crosshair Editor** (presets, sliders, a 15×15 grid), **Freelook** (hold Left Alt; toggle and
invert options; no per-server blocking, by choice), **Weather Changer** (client weather and a held
time of day), **Low Shield**, and **1.7 Animations** (block-hit and held-item poses only).

## Decision

### Framework additions

- **Events:** `CROSSHAIR` (draw your own; the game's is then skipped), `TURN` (the mouse's turn,
  consumable), `CAMERA` (yaw and pitch, settable without turning the player), `HELD_ITEM` (per hand
  in first person: lower, legacy poses, swing while using). Each is one reused object and costs a
  length check when nobody listens.
- **`Game`:** `attackStrength()`, `onTarget()`, `perspective()`/`setPerspective()`, `overrideSky(Weather, timeOfDay)`.
- **`GameOption.ATTACK_INDICATOR`**, held by index; options gained `max()` so a held choice survives.
- **`GridSetting`** with `GridScreen` (draw, erase, clear, reset, preview).
- **`HudCanvas`:** coloured `disc`/`ring` and `arc`.

### Modules

| Module | Category | What it does |
| --- | --- | --- |
| Cooldown Indicator | Combat | Holds the game's indicator Off and draws the charge as a bar, ring or percentage; hidden when charged unless on a target |
| Crosshair Editor | Visual | Cross, T, circle or dot from sliders, or a pixel grid; colour, opacity, on-target colour; presets |
| Freelook | Visual | Left Alt (hold or toggle) turns the camera, not the player; release restores the view |
| Weather Changer | Visual | Clear, rain, thunder or snow, and an optional held hour. Answers where the client reads weather; the server's weather runs on underneath |
| Low Shield | Visual | First-person shield lowered (default 35%), optionally only when not blocking |
| 1.7 Animations | Visual | Block-hit swing; 1.7's flat item, rod and sword-blocking poses (where swords block, ADR 0020) |

The **block-hit** only animates the arm on the client (`LivingEntity.swing`); no extra packet reaches
the server. The **1.7 poses** are 1.7's own transforms, applied after undoing today's first-person
display transform. Their numbers were checked against Animatium (a PolyForm Shield mod); none of its
code is used, only the figures, which are Mojang's own.

### Patches

Four: crosshair blit skip (`Hud.extractCrosshair`); turn and camera (`MouseHandler.turnPlayer`,
`Camera.alignWithEntity`); held item (`FirstPersonHandsAndItemsRenderer`, four hooks); weather
(`Level`, `WeatherEffectRenderer`, `ClientClockManager`). Cooldown Indicator needs none.

## Consequences

- Four patches, 42 changed lines.
- `smokeVisualModules` screenshots every crosshair preset, the cooldown bar and ring (checking the
  game's option is held and given back), weather over the server's rain, Freelook, Low Shield and
  1.7 poses. `TestGame` gains matching fixtures. `newmodules.steps` covers the page.

## Not yet

- **Legal review of 1.7 Animations' figures before release** (numbers from Animatium, a
  PolyForm Shield mod, though they are Mojang's own).
- 1.7 Animations isn't checked beside a real 1.7.10 client; the bow's pull isn't 1.7's.
- Crosshair Editor blends normally; the game's inverts what's behind it.
- Weather Changer's held time applies to the dimension's day clock only.
