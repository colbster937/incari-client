# tools/survey

- Runs on exactly JDK 25: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./incari survey`.
- `docs/port-backlog.md` is generated; change `rules.txt` and rerun (`--reclassify` is seconds),
  never edit the backlog by hand. It must never contain game code.
- Test: `./gradlew :tools:survey:test`.
