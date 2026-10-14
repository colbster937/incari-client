#!/usr/bin/env bash
# Validates every GLSL shader Incari tracks with glslang. Incari shaders are named by stage
# (.vert, .frag, .comp) so glslang never has to guess; shared .glsl includes are checked through
# the shaders that include them. Translated GLSL ES output is validated where it's generated, in
# the render-webgl2 build.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

shaders=$(git ls-files --cached --others --exclude-standard -- '*.vert' '*.frag' '*.comp' | grep -v '^work/' || true)
if [ -z "$shaders" ]; then
  echo "GLSL check passed: no Incari shaders yet."
  exit 0
fi

if ! command -v glslangValidator >/dev/null; then
  echo "glslangValidator is needed to check shaders (package: glslang)." >&2
  exit 1
fi

failed=0
count=0
while IFS= read -r shader; do
  count=$((count + 1))
  if ! output=$(glslangValidator "$shader" 2>&1); then
    echo "$output" >&2
    failed=1
  fi
done <<< "$shaders"

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "GLSL check passed: $count shaders valid."
