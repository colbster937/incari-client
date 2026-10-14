# features

One project per client feature (`features/<name>`, ADR 0046). Each is a `Module` from `:framework`.

- **Depend on `:framework` only.** Never the game, the platform or another feature; each feature has
  a `BoundaryTest` saying so. If the framework can't tell or let you do something, add that to the
  framework (its `AGENTS.md`), not a way around it.
- **Declare, don't wire.** Settings as fields (`settings().bool(...)`), HUD elements with `hud(...)`
  and listeners with `on(...)` in the constructor. Never draw a settings screen: it's generated.
- **Colors by tone.** Draw HUD text and panels in a `Tone`; a fixed color only from a `ColorSetting`
  the player chose. The theme decides the rest, so the client's restyle reaches every feature.
- **Hot paths don't allocate**: event listeners and `HudElement.draw`. Make strings once, keep
  state in fields.
- **Ids are forever**: the module's, each setting's and each HUD element's are config keys.
- **Names and descriptions** are short and plain: a setting's description is one sentence, shown as
  its tooltip.
- **Test with `TestGame`** (`:framework`'s test fixtures): enable the module, fire events, draw the
  HUD on its recording canvas.

## Adding a feature

1. `features/<name>/build.gradle.kts` as `togglesprint`'s, and its sources under
   `incari.features.<name>`, with a `BoundaryTest` and tests.
2. `include(":features:<name>")` in `settings.gradle.kts`; a `:features:<name> :framework` line in
   `gradle/module-boundaries.txt`, and `:features:<name>` added to `:client`'s line there.
3. `implementation(project(":features:<name>"))` in `client/build.gradle.kts`, and the module in
   `incari.client.modules.Features`.
4. `./gradlew :features:<name>:check`, then `./gradlew :client:strictCompile`.
