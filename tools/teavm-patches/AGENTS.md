# tools/teavm-patches

- TeaVM's code under its Apache 2.0 header. Change only what a fix needs; describe it in README.md.
- Never put Incari code in `src/main`; it's excluded from formatting and lint. The tests in
  `src/test` are Incari code, formatted and linted as usual.
- Test class library fixes against the JDK's behaviour: `./gradlew :tools:teavm-patches:check`.
