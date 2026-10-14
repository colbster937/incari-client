# client

- Game changes are patches: edit `work/client`, commit there (`<subsystem>: <summary>`), run
  `./incari rebuild`. Never edit `patches/` by hand.
- A patch is a one- or two-line hook into Incari code, wrapped in `// Incari start: <reason>` and
  `// Incari end`. Decompile fixes (`decompile:` subsystem) are the only unmarked patches.
- Incari code in `src/main/java` is adapters only: logic goes in a strict module. It may use game
  types and `platform-api`, never LWJGL or TeaVM (`BoundaryTest`).
- Test: `./gradlew :client:check` (needs `./incari setup`).
- Incari's screens get their widgets from `incari.client.ui` (`Ui`, `Theme`, `ControlList`,
  `ListScreen`), never by making the game's widgets themselves: the restyle changes that
  package and the screens follow. Colors and sizes come from `Theme`, not literals.
- The game's own screens are changed with `ScreenEdits` (ADR 0047): `edit` to move, remove or add
  widgets after each layout, `replace` to show an Incari screen instead. Prefer that to a new patch
  per screen. How the GUI looks is a `GuiSkin` (`Skins.use`), never per-screen drawing code; skin
  methods are hot paths.
- Feature logic lives in `features/*` (ADR 0046); `incari.client.modules` only adapts the game to
  `:framework`.
