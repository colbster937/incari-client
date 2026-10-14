# app-desktop

- Wiring only: install `platform-desktop`, start the game. Logic belongs in a strict module.
- Running the game needs a GPU and a display; without them it builds but can't run.
- After changing the platform layer or its hooks, run `./gradlew :app-desktop:smokeTest` and read
  `run/smoke-host/incari-smoke/report.txt` (and look at the screenshots).
