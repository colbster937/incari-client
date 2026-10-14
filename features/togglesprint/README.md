# togglesprint: Toggle Sprint

Latch sprint (or always sprint) and optionally sneak, with a HUD line saying what's active. Whether you can sprint is still the game's call. The framework's first module. ([ADR 0046](../../docs/adr/0046-module-framework.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Sprint key | Hold, Toggle, Always | Toggle |
| Sneak key | Hold, Toggle | Hold |
| Show status | on/off | on |
| Custom text color | on/off (shown with the status) | off: the theme's text color |
| Text color | color (shown with a custom color) | white |
| HUD: Status | position and scale | top left, 1x |

```sh
./gradlew :features:togglesprint:check
```
