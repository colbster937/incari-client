# shieldtotem: Shield & Totem HUD

Beside the hotbar: your shield's durability (and "Disabled" during an axe cooldown), and how many totems you carry. ([ADR 0052](../../docs/adr/0052-hud-modules.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Show shield | on/off | on |
| Color by wear | on/off | on |
| Show totems | on/off | on |
| Warn with none | on/off (a red x0; off hides it) | on |

```sh
./gradlew :features:shieldtotem:check
```
