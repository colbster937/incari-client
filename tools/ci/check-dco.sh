#!/usr/bin/env bash
# Fails if any commit in <base>..<head> lacks a Developer Certificate of Origin sign-off.
# Usage: tools/ci/check-dco.sh <base> <head>
set -euo pipefail

base=$1
head=$2
missing=0

for commit in $(git rev-list --no-merges "$base..$head"); do
  author_email=$(git log -1 --format='%ae' "$commit")
  if ! git log -1 --format='%(trailers:key=Signed-off-by,valueonly)' "$commit" | grep -qiF "<$author_email>"; then
    echo "missing sign-off by its author: $(git log -1 --format='%h %s' "$commit")" >&2
    missing=1
  fi
done

if [ "$missing" -ne 0 ]; then
  echo >&2
  echo "Every commit needs 'Signed-off-by: Name <author email>' (git commit -s). See CONTRIBUTING.md." >&2
  echo "To sign off existing commits: git rebase --signoff $base" >&2
  exit 1
fi
echo "DCO check passed."
