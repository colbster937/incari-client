# framework

- No game, platform or TeaVM types (`BoundaryTest`): features see the game only through this
  module. A new capability is defined here in Incari's terms and filled in by `:client`'s
  `incari.client.modules`.
- Compiled into the browser build: no reflection, no blocking, nothing TeaVM's class library lacks.
- Firing events and drawing the HUD are hot paths: don't allocate there. A channel nobody listens
  to must cost no more than a length check.
- A new event: a constant in `Events` (next index, `COUNT` up), a listener interface, a `fire` method
  on `EventBus`, and its hook in the game. Add it with the first module that needs it, and list it
  in ADR 0046.
- A new kind of setting: a `Setting` subclass and a `SettingVisitor` method; the compiler then shows
  every place to handle it (`ProfileCodec`, `:client`'s `SettingControls`).
- Features read the game through `Game` (`ModuleSystem.game()`, ADR 0050): views the client
  reuses, never game objects. A game option a module changes is held with `Module.holdOption`, never
  set and forgotten; a part of the game's HUD a module moves is a `GamePart` with a `GamePartElement`.
- What several features share and isn't a setting lives here, kept apart from profiles: the world's
  waypoints (`ModuleSystem.waypoints()`, ADR 0051). What follows the camera is drawn every frame: a
  live `HudElement`, or the `OVERLAY` event with `WorldOverlay.project`.
- Profile files must stay readable by older and newer builds: never rename an id, and keep reading
  forgiving (`ProfileCodec`).
- Public types get Javadoc, with their threading rules.
- Test: `./gradlew :framework:check`; end to end, `./gradlew :app-desktop:smokeModules`.
