# tools/survey: the TeaVM compile survey

Compiles the whole client with TeaVM and records everything between it and a browser build:
missing classes and members, JDK classes TeaVM lacks, natives and reflection. Each finding is
triaged as **stub**, **shim**, **rewrite** or **delete** into
[`docs/port-backlog.md`](../../docs/port-backlog.md). `tools/webbuild` uses the same rules.

```sh
./incari fetch                                              # once
JAVA_HOME=<JDK 25> ./incari survey                          # about a minute
JAVA_HOME=<JDK 25> ./incari survey --reclassify             # re-triage the last run, seconds
```

`INCARI_SURVEY_OPTS=-Xmx20g` raises the heap (default 12 GB).

## How it works

1. **Class path:** the client jar plus libraries `rules.txt` marks `keep`. Libraries marked
   `replace` or `delete` (LWJGL, JNA, OSHI…) stay off, so each call into them is a finding.
2. **Roots:** every method of every client class is an entry point (except `no-root` packages such
   as data generators), since following `main()` misses code behind callbacks.
3. **Host JDK hidden,** so JDK classes TeaVM lacks are reported, not silently compiled in.
4. **Stub rounds:** missing symbols get default-returning stubs, and it recompiles until nothing new
   turns up. Stubs are for analysis only; everything stubbed is still a finding.
5. **Scan and triage:** a bytecode scan adds reflection and missing-class references; each finding
   takes the first matching rule in `rules.txt`.

## Files

| File | What |
| --- | --- |
| `rules.txt` | The policy: which libraries compile in, which packages aren't roots, a triage rule per work item |
| `Main`, `Roots`, `TeaVmSurvey`, `Stubs`, `BytecodeScanner`, `Backlog` | Orchestration, roots, compile rounds, scan, markdown |
| `docs/port-backlog.md` (tracked) | Counts and a table per work item: API and package names only, never game code |
| `work/survey/<version>/` (ignored) | Every call site, and the saved result `--reclassify` reuses |

## Invariants

- **Exactly JDK 25.** TeaVM can't read Java 26 classes, and "missing" depends on the JDK.
- **Deterministic:** sorted, no timestamps.
- **Every library has a rule,** and no committed backlog has unclassified findings.

## How to test

```sh
./gradlew :tools:survey:test     # rules, stubs and roots; the full survey runs by hand
```
