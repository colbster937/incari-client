# ADR 0046: The module framework

- Status: accepted
- Date: 2026-10-09

## Context

The client's features number in the dozens, many written in parallel by agents. Each needs an on/off switch,
keybinds, settings with a screen, a HUD place, game events, and saved state. This builds that once:
done when a sample module's settings screen and keybind come from its schema alone.

The look will be redone, so nothing may tie features to the game's widgets. And on a
Chromebook, a feature that's off must cost nothing, and one that's on must not allocate on hot paths.

## Decision

- **`:framework`**, a strict module with no game types: `Module`, `ModuleSystem`, the event bus,
  settings, HUD elements, profiles. Only the JDK, JSpecify and Gson's tree API (`BoundaryTest`).
- **Features are `features/<name>` projects** depending on `:framework` only. `:client` lists them
  in `incari.client.modules.Features`: no reflection or service loading (the browser's closed world
  can't see them). Toggle Sprint is the first.
- **Adapters in `incari.client.modules`** are the only code seeing both sides: hooks fire events,
  `ModuleHud` draws, `GameKeys` reads binds.
- **A module declares; the framework runs it:** settings as fields, HUD elements and listeners in
  the constructor. Listeners are subscribed only while enabled.
- **Events** are channels with their own listener interface (`TICK`, `MOVEMENT`, `JOIN`, `LEAVE`;
  later ADRs add more). Listeners are an array replaced on (un)subscribe: firing allocates nothing,
  and an unheard channel costs a length check. A listener that throws is logged and its module
  turned off.
- **Settings are a typed schema:** boolean, integer, decimal, choice, colour, keybind; each with an
  id (config key), name, tooltip, default, optional `visibleWhen` and `hidden()`. `SettingVisitor`
  makes a new kind fail to compile wherever it isn't handled. Reads are primitive.
- **Keybinds use the game's key handling:** each `KeySetting` gets its own `KeyMapping`, outside
  Controls and `options.txt`. Every module has a toggle key (unbound by default). The module menu is
  Right Shift.
- **HUD elements** draw on a `HudCanvas` in GUI units. Placement (anchor of nine, offsets, scale) is
  settings saved with the profile; offsets are hidden, for the editor (ADR 0049). In the browser
  they're drawn into the HUD's cached texture (ADR 0030).
- **Colours are tones** (`Tone`: text, muted, accent, positive, warning, negative, background) that
  the theme resolves. Player-picked `ColorSetting`s are drawn as given.
- **Profiles:** named JSON sets of module states and settings in `incari/modules/profiles/`. A new
  one copies the active one. Saved a second after the last change, or at once on closing a module
  screen or leaving a world. Reading is forgiving: bad values keep defaults, broken files aren't
  overwritten until something changes, unknown modules keep their entries.
- **The UI is generated through one seam:** `incari.client.ui` (`Theme`, `Ui`, `ControlList`,
  `ListScreen`, `SettingControls`) builds the module menu, settings screens and profiles screen. The
  restyle replaces `Theme` and `Ui`, and every screen follows.
- **Two patches:** `KeyboardInput.tick` (keys to `ModuleInput`) and `Hud.extractRenderState`
  (`ModuleHud` before chat). Ticks, the menu key and the pause button use existing hooks.

## Not yet (at the time)

- World-render and packet events come with their first users, in the framework's own terms (done in
  ADRs 0050–0053).
- **Server feature control**: `Module.setEnabled` is the one way on, so a server veto goes there.
- **Language files:** names are English for now.

## Consequences

- A feature is one class plus tests, a dependency line and a line in `Features`. `TestGame` fakes the
  game for tests.
- `smokeModules` (desktop) and `modules.steps` (page) check it end to end.
