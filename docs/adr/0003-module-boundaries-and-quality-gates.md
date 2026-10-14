# ADR 0003: Enforced module boundaries and one set of quality gates

- Status: accepted
- Date: 2026-09-26

## Context

Incari is two halves: Mojang's decompiled game, which is touched as little as possible, and
Incari's own code, which people and agents write in parallel streams (graphics, platform,
network, features). Without mechanical checks, the architecture erodes one convenient import at a
time. It's also easy to paste decompiled code into an Incari module, which is a quality problem
and a legal one.

## Decision

- **Modules and their allowed dependencies are one file**, `gradle/module-boundaries.txt`.
  `./gradlew checkModuleBoundaries` (part of `check`) fails on an undeclared project dependency
  and on a module the file doesn't list. ArchUnit tests in each module enforce the package-level
  rules a Gradle dependency can't express: `platform-api` uses only the JDK and JSpecify, the
  platforms see no game types, and Incari's game-side code never touches LWJGL or TeaVM directly.
- **One set of gates for all Incari code**, run by `./gradlew check`, `npm run check`, CI and the
  pre-commit hook:
  - Java: Palantir Java Format via Spotless; `-Xlint:all -Werror`; Error Prone; NullAway in
    JSpecify mode, where unannotated means non-null (`AnnotatedPackages=incari`).
  - TypeScript: `strict` plus stricter compiler options, typescript-eslint's strict type-checked
    rules with zero warnings allowed, `any` an error, a reason required on every disable comment,
    Prettier.
  - GLSL: glslang validates every Incari shader.
  - The verbatim-copy check (`./incari copycheck`) fails on 60-token runs identical to the
    decompiled game.
  - The patch budget (`patches/budget.properties`) makes growth in vendor edits a deliberate,
    reviewed change.
- **Vendor code is exempt from all of it.** It's never formatted, linted or fixed for warnings.
  `:client` compiles it with lint off, and `strictCompile` rechecks Incari's sources in that
  module with the full gates.
- **The build runs on JDK 25** (`gradle/gradle-daemon-jvm.properties` and the toolchain), the
  same as CI and TeaVM's limit, so formatter and compiler results don't depend on the machine.
- **Human review** is required through `CODEOWNERS` for patches, graphics, networking, licensing
  and the guards themselves.

## Alternatives rejected

- **Convention plugins in `build-logic`**: cleaner at scale, but a second build to maintain for
  about thirty lines of shared configuration. Revisit when features add many modules.
- **Checkstyle or Spotless-only rules for architecture**: they can't see the dependency graph.
- **A `-Werror`-free `:client`**: would let warnings pile up in Incari's adapter code, the one
  place Incari and vendor code meet.
- **An allowlist for copycheck findings**: every finding is fixed by rewriting, never excused.

## Consequences

- Adding a module means a line in `module-boundaries.txt` and an ArchUnit test; changing a line
  needs an ADR.
- `./gradlew check` needs `./incari setup` first, because `:client` compiles the patched tree.
- Upgrading Palantir, Error Prone or TeaVM past what JDK 25 supports means moving the whole
  build's JDK, not one tool.
