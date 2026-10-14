# ADR 0001: The repo is patch-only, built on the Spigot/Paper model

- Status: accepted
- Date: 2026-09-25

## Context

Incari modifies Minecraft's client, and Mojang's code and assets can't be redistributed. Playable
Eaglercraft repositories were taken down in 2023, and the EaglercraftX workspace now returns
HTTP 451 on GitHub. Spigot's BuildTools has survived since 2014 by shipping only patches plus a tool
that downloads and decompiles the game on the user's machine.

## Decision

- The repo tracks only Incari's own code, patch files, and a manifest of whole-class changes.
- `./incari setup` downloads the pinned client jar from Mojang, decompiles it with Vineflower into
  the git-ignored `work/`, and applies the patches. A fresh clone reaches a patched tree with that
  one command.
- Game edits are commits in `work/client`. `./incari rebuild` turns them into patch files. Patch
  files are never edited by hand.
- Patches carry as little Mojang text as possible: one context line, no hunk-header function names,
  and no file deletions. Deleting or replacing a whole class goes through `patches/classes.txt`,
  which names the class and contains none of its code.
- The version, the client jar's SHA-1 and the Vineflower version are pinned exactly, so every
  machine decompiles the same bytes and the patches apply everywhere.
- CI never publishes a build (no artifacts, releases or pages), and a guard script enforces it.

## Alternatives rejected

- **Commit the decompiled source on a vendor branch** (the original plan's first sketch): simplest to
  rebase, but it puts Mojang's code in the repo.
- **A binary patch against the jar** (Paper's Paperclip): works for JVM bytecode, but Incari's output
  is a TeaVM compile. The source tree has to exist locally anyway.
- **Full diff context** (`-U3`, the git default): more robust to drift, but triples the Mojang lines
  in the repo. `git am --3way` with full blob ids recovers most of that robustness.

## Consequences

- Updating to a new drop means re-pinning `incari.properties`, then `./incari setup`, fixing
  conflicts in `work/client`, and `./incari rebuild`. Automating this is planned.
- Anyone building Incari needs network access to Mojang's download hosts.
- Patch files still contain a few Mojang lines as diff context. Keeping patches thin (one- or
  two-line hooks into Incari modules) keeps that small; CI holds patches to a size budget.
