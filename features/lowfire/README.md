# lowfire: Low Fire

Draws the fire overlay lower while you burn, so it hides less. The fire itself is unchanged. ([ADR 0050](../../docs/adr/0050-first-modules-game-view.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Lower by | 0-25%, in flame heights | 10%: the flames keep to the bottom fifth |

```sh
./gradlew :features:lowfire:check
```
