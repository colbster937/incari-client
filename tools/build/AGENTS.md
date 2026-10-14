# tools/build

- Output must stay deterministic: the same inputs give byte-identical patch files on every
  machine. Don't add timestamps, machine paths or locale-dependent formatting to anything written.
- Nothing Mojang may leave `work/`. Every such path is defined in `Workspace`.
- Test: `./gradlew :tools:build:test`. The end-to-end check is `./incari setup && ./incari rebuild --check`.
