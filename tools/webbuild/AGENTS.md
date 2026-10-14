# tools/webbuild

- Change what's removed through `tools/survey/rules.txt`, not code here.
- A link error in a stub or shim symbol is fixed in `web-classlib`, never by removing it.
- Test: `./gradlew :tools:webbuild:check`; run for real with `./gradlew :app-web:teavm`.
