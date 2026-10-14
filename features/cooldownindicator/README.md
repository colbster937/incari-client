# cooldownindicator: Cooldown Indicator

Shows the attack cooldown as a bar, ring or percentage, in place of the game's indicator (which is held Off while this is on). ([ADR 0053](../../docs/adr/0053-visual-and-combat-modules.md))

| Setting | Kind | Default |
| --- | --- | --- |
| Style | Bar, Ring, Percent | Bar |
| Bar width / height | 8-80 / 1-6 | 24 / 2 |
| Ring size | 6-24 | 10 |
| Hide when charged | on/off | on |
| Show charged on target | on/off | on |
| Custom colors | on/off, two colors | off |

```sh
./gradlew :features:cooldownindicator:check
```
