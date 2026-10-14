# ADR 0004: The game is built from the patched source tree, starting with decompile fixes

- Status: accepted
- Date: 2026-09-26

## Context

The TeaVM survey compiled Mojang's jar, not source. Everything after it needs the game compiled
from `work/client`, because that's where Incari's patches live: the desktop runtime and the
TeaVM build both start from it. Vineflower 1.12.0's output of 26.3 doesn't compile as-is:
javac reports 233 errors in 119 files. About 90 are annotation jars Mojang compiled against but
doesn't ship; the rest are generics, casts and variable scopes the decompiler lost. Vineflower's
`--explicit-generics` makes it worse (1,000 errors).

## Decision

- **`:client` compiles `work/client`** as its main source set, against Mojang's own library jars
  (the list `incari fetch` writes to `work/cache/versions/<version>/classpath.txt`, after checking
  each jar's SHA-1), plus JetBrains annotations and JSR-305 as compile-only dependencies.
- **Decompile errors are fixed by patches in the `decompile` subsystem**, applied first. They
  restore what the original source must have been (a cast, a type witness, a renamed duplicate
  local, a missing enum case) and change no behavior; where behavior was in doubt, the bytecode
  decided. They are **not** wrapped in `// Incari start`/`// Incari end`: those markers identify
  Incari's changes to the game, and decompile fixes aren't changes to the game.
- **Vendor warnings stay.** The tree is compiled with lint off, and a warning in Mojang's own code
  (26.3 has one) is never patched away.
- **The decompiler options stay at Vineflower's defaults** (plus formatting). Any change to them
  regenerates every file and invalidates every patch.

## Alternatives rejected

- **Compile against Mojang's jar and patch only bytecode**: patches would be bytecode transforms
  instead of readable source diffs, and the desktop debugger would show decompiled, not patched,
  source.
- **Replace every broken class through `classes.txt`**: an Incari-authored copy of a Mojang class
  is exactly the verbatim copying the repo forbids.
- **Tune Vineflower until the output compiles**: tried with `--explicit-generics`, which made it
  worse; each new drop would need retuning anyway.

## Consequences

- Each Minecraft drop starts with fixing the new decompile errors in `patches/decompile/`. The
  Future update automation would treat "the tree compiles" as its first gate.
- The compiled client matches the jar class for class (11,383 classes for 26.3), which the desktop
  desktop runtime relies on.
