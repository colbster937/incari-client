# ADR 0051: Minimap and waypoints

- Status: accepted
- Date: 2026-10-10

## Context

Players asked for a good-looking **minimap**, and **waypoints** with a name, colour and icon, shown
on the minimap and over the world with their distance and an optional beacon-like beam. Both must be
easy to use and cheap on the reference Chromebook.

The framework couldn't yet tell a feature where the player is, draw more than text and rectangles,
follow the camera, draw into the world, keep non-setting data, or open screens. And the browser
refreshes the HUD only a few dozen times a second (ADR 0030), so anything that turns with the camera
would lag.

## Decision

### Framework additions

- **Player position:** `PlayerView.x/y/z()` (interpolated), `heading()`, `dimension()`, `isDead()`;
  `Game.world()` names the world stably (`server/<address>`, `world/<folder>`).
- **Waypoints belong to the framework** (`incari.framework.waypoint`), since minimap and waypoints are
  separate features. One file per world, `incari/modules/waypoints/<world>-<hash>.json`, saved a
  second after a change, independent of profiles. `ModuleSystem.waypoints()`.
- **Drawing:** `HudCanvas.shape`, `disc`, `ring`, `terrain` (the minimap), the `EDGE` tone, fractional `push`.
- **Live HUD elements** (`HudElement.isLive()`): drawn every frame with the crosshair, not with HUD refreshes.
- **Events:** `OVERLAY` every frame on a `WorldOverlay` (with `project`: a world point on screen, or
  which way to turn); `WORLD` during the world's submit, on a `WorldCanvas` (`beam`).
- **Screens:** `Game.openWaypoints()`, `Game.editWaypoint()`; `ActionSetting` for a button in settings.

### Modules

| Module | What it does |
| --- | --- |
| Minimap (HUD) | Live map; round or square; turning or north-up; 7 zooms (= and -); waypoints (on the edge when beyond); compass; coordinates; world map on M; explored map remembered |
| Waypoints (Utility) | A glass tag per waypoint (icon, name, distance), a beam, edge arrows for those off screen; N marks here, U lists them; deaths leave one |

- **Tags** are drawn on the overlay, not in the world: crisp at any distance, never hidden by
  terrain, placed every frame. Off-screen waypoints get arrows on the screen's edge, spread apart.
- **Beams** are the game's beacon beam, so they look like one and terrain hides them. Beyond the fog
  they're moved in along the line of sight and shrunk, so a waypoint 600 blocks off still shows.
- **Minimap terrain** (`MapTerrain`): a wrapping 512×512 texture, one texel per block. Each chunk is
  drawn once into its square (map colour, biome tint, slope shading, water depth). Under a ceiling
  (the Nether) it's a slice at the player's height. Up to 1.5 ms a tick, nearest first; nearby chunks
  redrawn every half second. Drawn as a triangle fan, so turning is free; no custom shader.
- **Screens:** the list (search, nearest first, hide, edit, delete with a second click) and the
  editor (tag preview, 12 colours or hex, 8 icons, coordinates, Here, beam). Frost look; new icons.

### World map and remembered map

- **World map** (`WorldMapScreen`, M): drag, zoom round the pointer, the player as an arrow, waypoint
  tags. Click a waypoint to edit; right-click to add one. Each 512×512 region is a texture made as it
  comes into view; up to 48 kept while open.
- **Remembered map** (`MapMemory`): every chunk the minimap draws is kept as its 256 colours, by
  32×32-chunk region, per world and dimension. With **Remember explored map** on (default), regions
  are saved to `incari/map/<world>/<dimension>/<x>.<z>.map` (deflated RGB, 100–250 KB a full
  region). Only top-down colours are kept: chunks would be tens of times larger.

### Patches

Two: the live HUD beside the crosshair (`Hud.extractRenderState`), and the world event after
particles (`LevelRenderer.submitFeatures`).

## Consequences

- Two patches, 6 lines.
- `smokeWaypoints` and `waypoints.steps` cover both modules, the screens, the world map and saving.
- Remembered maps grow with exploring (100–250 KB per 512×512 blocks).
- Cost while on: ≤ 1.5 ms a tick drawing new chunks, 1 MB of texture, a few draws per waypoint a
  frame. Off: nothing.

## Not yet

- Cave maps in the Overworld; entities on the map; another dimension's world map.
- Waypoints shared from servers or in chat; per sub-world sets.
- Teleport to a waypoint; sorting the list by name.
