# ADR 0050: The first modules, and what they read of the game

- Status: accepted
- Date: 2026-10-10

## Context

The next modules players asked for are PvP and QoL staples: **Low Fire**, **No Hurt Cam**, **Armor &
Potion View** (with readable durability), **Clear Chat** and **Custom Scoreboard** (the server's
sidebar, placed by the player). Each needs something the framework didn't expose: equipment and
effects, game options, a game HUD part to move, and a say over the fire overlay. As ADR 0046 requires,
all in the framework's terms, no game types.

## Decision

### Framework additions (`incari.framework.game`)

- **`Game`** (`ModuleSystem.game()`): the local player (`PlayerView`), a sample player for the HUD
  editor, and game options. `Game.NONE` outside a game; `TestGame` with a `FakePlayer` in tests.
- **`PlayerView`, `ItemView`, `EffectView`:** equipment per slot (count, damage, max) and effects as
  the game's HUD shows them (name, level, ticks left, category). Reused holders; names made once per
  language, so reading them allocates nothing.
- **`HudCanvas.item`, `HudCanvas.effect`:** draw item and effect icons (with durability bar and count).
- **Held options, `Module.holdOption(GameOption, value)`:** while on, the option takes the module's
  value. Off gives back the player's (kept in a hidden setting across restarts). Moving the game's
  slider turns the module off and keeps the player's choice. So far `TEXT_BACKGROUND_OPACITY`,
  `DAMAGE_TILT`.
- **Game parts, `GamePartElement(GamePart)`:** draws a game HUD part (`SCOREBOARD`) where the player
  places it; the game skips it while held. Sized as the game draws it, so the editor works normally.
- **`Events.FIRE_OVERLAY`:** listeners may lower the flames.

### Modules

| Module | Category | What it does |
| --- | --- | --- |
| Low Fire | Visual | Fire overlay lowered 0–25% (default 10%; measured: at 30% it's gone) |
| No Hurt Cam | Visual | Holds Damage Tilt at 0% |
| Armor & Potion View | HUD | Armor and held item with durability (% / uses / of max / none; green, amber below half, red below a fifth); effects with level and time left |
| Clear Chat | HUD | Holds Text Background Opacity at 0 (not Chat Text Opacity, which would hide chat) |
| Custom Scoreboard | HUD | The server's sidebar as a placeable, scalable element |

Strings are made only when what they say changes.

### Patches

Two: the fire overlay's pose lowered (`ScreenEffectRenderer.submit`), and the sidebar handed to
`GameParts` (`Hud`). Clear Chat and No Hurt Cam need none.

## Consequences

- Two patches, 17 changed lines.
- `smokeFeatures` checks all five in a world (options held and given back, a slider turning Clear
  Chat off, the sidebar moved) and screenshots them.
- More game data goes into `PlayerView` or `GameOption`; more movable HUD parts are `GamePart`s
  with two hooks each.

## Not yet

- The game's own effect icons (top right) still show beside Armor & Potion View.
- Custom Scoreboard's extras (hide red numbers, background opacity) need hooks inside the sidebar.
- Only the main hand is shown with the armor.
