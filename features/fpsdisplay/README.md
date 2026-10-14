# fpsdisplay: FPS Display

Frames drawn in the last second. ([ADR 0052](../../docs/adr/0052-hud-modules.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Format | 120 FPS, FPS: 120, 120 | 120 FPS |
| Color by speed | on/off (green from 60, amber from 30, red below) | off |

```sh
./gradlew :features:fpsdisplay:check
```
