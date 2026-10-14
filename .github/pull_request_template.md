## What

<!-- One concern per pull request. What does it change? -->

## Why

<!-- The issue or need, and the reason for this approach. -->

## Performance impact

<!-- Download size, load time, heap, frame time: numbers from the CI report or a local run, or
     "none" with a reason. Renderer and networking changes always need numbers. -->

## Screenshots

<!-- Visual changes only. Never attach a playable build or a link to one. -->

## Checklist

- [ ] `./gradlew check` and `npm run check` pass locally
- [ ] `./incari rebuild --check` and `./incari copycheck` pass (if Java or patches changed)
- [ ] Patches are one- or two-line hooks into Incari modules, marked `// Incari start: <reason>` / `// Incari end`
- [ ] No Mojang content, builds or links to playable builds; nothing copied from the decompiled game
- [ ] Tests cover the change, and the module README still describes it
- [ ] Every commit is signed off (`git commit -s`)
