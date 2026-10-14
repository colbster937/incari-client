# nohurtcam: No Hurt Cam

Stops the camera tilting when you're hurt by holding the game's Damage Tilt at the module's value. Moving that slider yourself turns the module off. ([ADR 0050](../../docs/adr/0050-first-modules-game-view.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Shake amount | 0-100% of the game's tilt | 0%: none |

```sh
./gradlew :features:nohurtcam:check
```
