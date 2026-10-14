#!/usr/bin/env bash
# Fails if any workflow could publish a build. Every Incari build contains Mojang code, so CI
# may compile and test, but must never upload artifacts, cut releases or deploy pages.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

workflows=$(git ls-files '.github/workflows/*.yml' '.github/workflows/*.yaml')
if [ -z "$workflows" ]; then
  echo "No workflows to check."
  exit 0
fi

# The pattern is spelled out in pieces so this script doesn't flag its own workflow file.
forbidden='actions/upload-''artifact|actions/upload-pages-''artifact|actions/deploy-''pages|action-gh-''release|gh release (create|upload)|(contents|packages|pages|deployments|id-token): *write'

# shellcheck disable=SC2086
if matches=$(grep -nE "$forbidden" $workflows); then
  echo "Workflows must never publish builds or request write access (CONTRIBUTING.md):" >&2
  echo "$matches" >&2
  exit 1
fi
echo "Publishing check passed: no workflow uploads, releases or deploys."
