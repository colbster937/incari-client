# ADR 0002: GPL-3.0-or-later with a Minecraft additional permission, and DCO sign-off

- Status: accepted
- Date: 2026-09-25

## Decision

- Incari's own code is licensed **GPL-3.0-or-later** (`LICENSE`) with an **additional permission
  under GPL section 7** (`LICENSE-EXCEPTION.txt`). It lets anyone convey Incari combined with
  Minecraft: Java Edition without having to provide Minecraft's source.
- Contributors certify the **Developer Certificate of Origin** (`DCO.txt`) with a `Signed-off-by`
  line on every commit. Signing off licenses the contribution under the project license, exception
  included, so no contributor's code can later be used against the builds. CI checks sign-offs on
  every pull request.
- DCO rather than a CLA: it's lighter for contributors, and the exception is part of the license
  every contribution is made under, so a separate grant isn't needed.

## Consequences

- The published source must keep `LICENSE`, `LICENSE-EXCEPTION.txt` and `DCO.txt` at the root.
- Third-party code copied into Incari must be GPL-3.0-compatible, and must allow the exception.
  Code under a license that forbids adding permissions can't be merged into Incari modules.
