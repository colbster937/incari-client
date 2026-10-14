#!/usr/bin/env bash
# Fails if git tracks anything that looks like Mojang content or a compiled build.
# The repo must stay patch-only (CONTRIBUTING.md); this is the mechanical half of that rule.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

violations=$(git ls-files -z | tr '\0' '\n' | awk '
  # The Gradle wrapper jar is the one jar allowed in the repo.
  $0 == "gradle/wrapper/gradle-wrapper.jar" { next }
  # Generated workspace: jars, decompiled source, patched tree.
  /^work\//                                  { print "generated workspace:  " $0; next }
  # Game code and compiled builds.
  /\.(jar|class|wasm|wasm\.map|teavmdbg)$/   { print "binary or build:      " $0; next }
  # Game assets and data.
  /(^|\/)assets\/minecraft\//                { print "Minecraft assets:     " $0; next }
  /(^|\/)data\/minecraft\//                  { print "Minecraft data:       " $0; next }
  /\.(ogg|nbt|mca|mcmeta)$/                  { print "game asset format:    " $0; next }
  # Mojang core shaders, translated copies included (Incari shaders use .vert/.frag/.comp).
  /\.(vsh|fsh)$/                             { print "Mojang shader format: " $0; next }
  /(^|\/)shaders\/core\//                     { print "Mojang core shaders:  " $0; next }
')

if [ -n "$violations" ]; then
  echo "These tracked files must not be in the repo (see CONTRIBUTING.md):" >&2
  echo "$violations" >&2
  exit 1
fi
echo "Repo content check passed: no Mojang content or builds tracked."
