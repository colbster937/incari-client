#!/usr/bin/env bash
# Reports the size of Incari's changes to vendor code and fails if it grows past
# patches/budget.properties. Every vendor line touched is rebase cost on the next Minecraft drop,
# and every context line is Mojang text in the repo, so growth has to be deliberate: raise the
# budget in the same pull request and say why.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

budget=patches/budget.properties
read_budget() {
  grep -E "^$1=" "$budget" | cut -d= -f2 | tr -d '[:space:]'
}

patches=$(git ls-files --cached --others --exclude-standard -- 'patches/*.patch' 'patches/**/*.patch')
patch_count=0
changed=0
context=0
if [ -n "$patches" ]; then
  patch_count=$(echo "$patches" | wc -l)
  # Inside hunks: "+"/"-" lines are changes, " " lines are vendor context.
  read -r changed context < <(echo "$patches" | xargs awk '
    /^@@ / { in_hunk = 1; next }
    /^diff --git / { in_hunk = 0; next }
    in_hunk && /^[-+]/ { changed++ }
    in_hunk && /^ / { context++ }
    END { print changed + 0, context + 0 }')
fi
replaced=$(grep -cE '^\s*replace\s' patches/classes.txt || true)
deleted=$(grep -cE '^\s*delete\s' patches/classes.txt || true)

echo "Patch budget:"
printf '  %-28s %6s / %s\n' "patch files" "$patch_count" "$(read_budget max.patches)"
printf '  %-28s %6s / %s\n' "changed lines (+/-)" "$changed" "$(read_budget max.changed.lines)"
printf '  %-28s %6s / %s\n' "vendor context lines" "$context" "$(read_budget max.context.lines)"
printf '  %-28s %6s\n' "classes deleted (manifest)" "$deleted"
printf '  %-28s %6s\n' "classes replaced (manifest)" "$replaced"

over=0
check() {
  local name=$1 value=$2 limit
  limit=$(read_budget "$name")
  if [ "$value" -gt "$limit" ]; then
    echo "Over budget: $name is $value, the budget is $limit." >&2
    over=1
  fi
}
check max.patches "$patch_count"
check max.changed.lines "$changed"
check max.context.lines "$context"

if [ "$over" -ne 0 ]; then
  echo "Make the patches thinner (move logic into an Incari module), or raise $budget in this" >&2
  echo "pull request and explain the growth in its description." >&2
  exit 1
fi
