# ADR 0028: The web folder (`./incari build`)

- Status: accepted
- Date: 2026-10-01

## Context

The goal: `incari build` writes a web folder and an offline HTML file, on Windows, macOS and
Linux. Until now a build ran only through the localhost dev server.

Every build contains Mojang's code, so the project never publishes one (ADR 0001, 0014). Once the
source is public, people will build anyway. They need a supported way to host their own build, and
a plain statement, wherever builds are seen, that they mustn't be shared.

## Decision

- **`./incari build [--out <dir>] [--only fast|compatible] [--no-sounds]`** (`WebBuild`) fetches
  sounds if missing, compiles the release builds, and runs `:app-web:webFolder`. Default output
  `dist/web/` (ignored). An output git would track is refused.
- **`WebFolder`** copies only what the page fetches (build files, page, manifest, pack, the jar's
  resources, sounds) and writes its own `build.json` over exactly those.
- **It only replaces what it wrote** (`.incari-web-folder` marker); a folder with other files is refused.
- **The notice travels with the build:** `README.txt` starts "DO NOT SHARE, UPLOAD OR PUBLISH THIS
  FOLDER" and explains why; `./incari build` prints it; [`docs/self-hosting.md`](../self-hosting.md)
  is the project's statement.
- **Server set-up is documented, not required:** relative URLs; `.htaccess` for Apache, nginx and
  Caddy snippets in `README.txt`. Without isolation headers, workers copy instead of sharing; over
  plain HTTP, no service worker and IndexedDB storage.

(The original `--js` option went with the JavaScript fallback, ADR 0036.)

## Consequences

- A release build replaces the development one in `app-web/build/`.
- The offline HTML file came later (ADR 0057).
- Via's licensing question (ADR 0002) is unchanged: hosting your own build is easier; publishing
  one stays forbidden.
