# theme: Theme Editor

The client's colors and font for menus and HUD: presets or your own, shared as a code. Always on; open it from **Theme** in the module menu's sidebar. ([ADR 0054](../../docs/adr/0054-screen-modules.md), [ADR 0062](../../docs/adr/0062-incari-settings-and-the-2026-10-12-batch.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Preset | Custom, Frost, Midnight, Ember, Mint, Rose, Mono | Frost |
| Accent / Text / Secondary text / Panels | colors | Frost's |
| Panel opacity | 40-100% | 86% |
| HUD background opacity | 0-100% | 42% |
| Corner rounding | 0-8 | 3 |
| Font | Red Hat Display, Minecraft, Inter, Nunito, Poppins, JetBrains Mono, Michroma | Red Hat Display |
| Export theme / Import theme | buttons | copy / paste a code |

```sh
./gradlew :features:theme:check
```
