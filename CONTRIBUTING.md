# Contributing to Incari

This repo holds only Incari's code and small patch files. The build tool downloads Mojang's game
and decompiles it on your machine, so the game itself never goes into git.

## Rules

These keep the project safe from takedowns. CI enforces what it can.

- **No Mojang content:** jars, decompiled source, textures, sounds, models, language files, shaders
  (translated copies too), data dumps.
- **No builds:** no `.wasm`/`.js` builds, offline HTML, releases, Pages or CI artifacts, and no
  links to hosted builds.
- **No copied game code** in Incari modules. Write it yourself.
- **No secrets.** Read them from environment variables.

## Setup

Needs JDK 25+, git and Node 24+.

```sh
./incari setup      # download 26.3, decompile, patch into work/client; installs the pre-commit hook
npm ci              # TypeScript tooling
```

Every `./incari` command: [`tools/build/README.md`](tools/build/README.md). Where things are:
[`ARCHITECTURE.md`](ARCHITECTURE.md). Why things are as they are: [`docs/adr/`](docs/adr/README.md).
For larger changes, open a discussion or write an ADR first.

## Changing game code

```sh
cd work/client
# edit, then commit one change per commit; the subject prefix becomes the folder under patches/
git commit -am "render: Route section compile to the mesher"
cd ../..
./incari rebuild    # writes patches/render/NNNN-route-section-compile-to-the-mesher.patch
```

Then commit the changed `patches/` files in this repo. Never edit patch files by hand.

- **Keep patches thin.** A patch should be a one- or two-line hook into an Incari module, marked
  `// Incari start: <reason>` … `// Incari end`. Put the logic in the module.
- **Never reformat vendor code.** Every changed line makes the next Minecraft update harder to rebase.
- **Deleting or replacing a whole class** goes in `patches/classes.txt`, not in a patch.
- **Decompile fixes** (`patches/decompile/`) only make the tree compile, so they need no markers (ADR 0004).
- **Patch budget:** `tools/ci/check-patch-budget.sh` checks total patch size against
  `patches/budget.properties`. If you have to raise it, do it in your PR and explain why.

## Before you open a pull request

```sh
./gradlew check              # format, Error Prone, NullAway, boundaries, tests, client build
npm run check                # TypeScript: types, lint, format, tests
./incari rebuild --check     # patches/ matches your work/client commits
./incari copycheck           # nothing copied from the game
```

`./gradlew spotlessApply` and `npm run format` fix formatting. A maintainer must review changes to
the renderer, networking, patches and licensing (`.github/CODEOWNERS`).

## Sign off your commits (DCO)

```sh
git commit -s -m "tools: Add the survey command"
```

Signing off says you agree to the [Developer Certificate of Origin](DCO.txt) and that your change is licensed
under GPL-3.0-or-later with the Minecraft exception ([`LICENSE-EXCEPTION.txt`](LICENSE-EXCEPTION.txt)).
CI rejects commits without a sign-off.

## Optional environment variables

| Variable | For |
| --- | --- |
| `INCARI_CHROME` | Which Chromium the browser checks and benchmark run |
| `INCARI_BROTLI` | The `brotli` command for `:app-web:checkDownloadSize` |
| `INCARI_RCON_PASSWORD` | Smoke tests that drive a local server over RCON |
| `INCARI_ONLINE_URL` | Which service `tools/onlinedev/peer.mjs` talks to |
| `INCARI_BENCH`, `INCARI_BENCH_OFF` | Turn on `TerrainStepBench` and pick what it compares |
| `INCARI_BUILD_OPTS`, `INCARI_SURVEY_OPTS`, `INCARI_COPYCHECK_OPTS` | JVM options for the `./incari` tools |
