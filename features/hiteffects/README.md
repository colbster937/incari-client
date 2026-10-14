# hiteffects: Hit & Kill Effects

Particles on whatever you hit, and a bigger burst with a sound when it dies. Only you see them. ([ADR 0061](../../docs/adr/0061-cosmetics.md))

| Setting | Kind | Default |
| --- | --- | --- |
| On hit | on/off | on |
| Hit effect | choice | Magic |
| Hit particles | 1-20 | 6 |
| On kill | on/off | on |
| Kill effect | choice | Firework |
| Kill particles | 5-60 | 30 |
| Kill sound | on/off | on |

```sh
./gradlew :features:hiteffects:check
```
