# tools/copycheck: the verbatim-copy check

Fails if an Incari Java file has a long run of code identical to the decompiled game. Pasting
from the decompiled tree is easy to do by accident, for people and agents. This is the mechanical
half of "write it fresh"; review is the other half.

```sh
./incari copycheck                 # after ./incari setup, ~10 seconds; CI runs it too
./incari copycheck --window=40     # stricter, for spot checks
```

`JavaTokens` lexes, `WindowIndex` hashes every 60-token window of the game, `CopyCheck` reports
matching runs.

## Invariants

- **Compares against the pristine decompiled source**, not the patched tree.
- **Checks every Java file outside `work/`** that git tracks or would track, so it works before a commit.
- **Formatting, comments, imports and `package` lines are ignored,** so reformatting doesn't hide a
  paste. Identifiers count.
- **A 60-token window** (5–8 lines). Shorter shared runs (signatures, common loops) are normal.
- **No allowlist.** A finding means a rewrite.

## How to test

```sh
./gradlew :tools:copycheck:test
```
