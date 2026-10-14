# crosshair: Crosshair Editor

Replaces the crosshair with your own: a cross, T, circle or dot from sliders, or a 15×15 pixel grid you draw. ([ADR 0053](../../docs/adr/0053-visual-and-combat-modules.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Preset | Custom, Classic, Competitive, Small, Dot, Circle, T | Classic |
| Style | Cross, T, Circle, Dot, Pixel grid | Cross |
| Pixel grid | grid editor | a plus |
| Length / Gap / Thickness | 1-30 / 0-20 / 1-10 px | 9 / 0 / 2 |
| Center dot / Outline | on/off | off / off |
| Color / Opacity | color / 10-100% | white / 100% |
| Color on target | on/off, a color | off |

```sh
./gradlew :features:crosshair:check
```
