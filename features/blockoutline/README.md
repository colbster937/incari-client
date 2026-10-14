# blockoutline: Block Outline

Outlines the block you're looking at in your own color and width, with an optional faint fill. Only you see it. ([ADR 0061](../../docs/adr/0061-cosmetics.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Color | color | `#A9DCFF` |
| Opacity | 10-100% | 90% |
| Width | 1-6 pixels | 2.5 |
| Fill | on/off | off |
| Fill color | color (when Fill is on) | `#A9DCFF` |
| Fill opacity | 5-60% (when Fill is on) | 20% |

```sh
./gradlew :features:blockoutline:check
```
