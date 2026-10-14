# ADR 0052: The HUD modules: Keystrokes, CPS, FPS, Reach, Coordinates, Shield & Totem

- Status: accepted
- Date: 2026-10-10

## Context

Next the player asked for the PvP clients' HUD staples: **Keystrokes**, **CPS**, **FPS Display**,
**Reach Display**, **Coordinates**, and a **Shield & Totem HUD**. Each needs something the framework
didn't offer: clicks, a hit's distance, held binds, frame rate, biome, item ids, cooldowns, counts.

## Decision

### Framework additions

- **Events:** `CLICK` (mouse presses over the world, no screen open) and `ATTACK` (an entity hit,
  with the reach from the eyes to where the crosshair met its box).
- **`ClickCounter`:** a ring of 64 press times; `perSecond(now)`.
- **`Game`:** `isDown(GameKey)` and `keyName(GameKey)` for the nine movement/attack/use binds as
  bound ("W", "LMB"); `fps()`; `millis()`; `item(id)`.
- **`PlayerView`:** `biome()` (localized, cached) and `count(id)`. **`ItemView`:** `id()` and
  `cooldown()`.
- **`TextElement`:** one line of text on glass, in a tone or a chosen colour, width kept steady as
  digits change. **`Tone.ON_ACCENT`.**
- `TestGame`/`FakePlayer` fake all of it.

### Modules

| Module | Shows | Default place |
| --- | --- | --- |
| Keystrokes | W A S D, LMB/RMB with clicks per second, space; lit while held | Top right, below the minimap |
| CPS | "12 \| 4 CPS" (left, right or both) | Left, stacked |
| FPS Display | "120 FPS" (three formats), optional colour by rate | Left, stacked |
| Reach Display | "3.04 blocks" for 3 s after a hit; long hits amber if asked | Left, stacked |
| Coordinates | X Y Z, facing and axis, biome, the matching Nether/Overworld spot | Left, below |
| Shield & Totem HUD | The shield's durability by wear, "Disabled" during an axe cooldown; totems carried | Beside the hotbar |

Keystrokes shows the player's own bindings (AZERTY reads "Z Q S D") and what the game reads as held,
not Toggle Sprint's latch. Text is rebuilt only when it changes; none of these is live.

### Patches

Two, three lines each: `MouseHandler.onButton` (clicks) and `Minecraft.startAttack` (attacks).

## Consequences

- Two patches, six changed lines.
- `smokeHudModules` clicks a villager, holds keys, and checks clicks, reach (~2.3 blocks), FPS,
  bind names, totem count, shield id and biome. `newmodules.steps` covers the page.

## Not yet

- Keystrokes lights at the HUD's refresh rate (every 50 ms in the browser), without a fade.
- Reach is measured client-side; a server's lag compensation may differ.
