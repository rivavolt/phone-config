#!/usr/bin/env bash
# Install only the packages from packages.txt that aren't already installed.
# Termux has no atomic upgrade — best we can do is diff against pkg list-installed.

packages_apply() {
  local list="$1"
  [ -f "$list" ] || die "packages list missing: $list"

  do_ "pkg update (metadata refresh)"
  pkg update -y >/dev/null

  local wanted installed missing
  wanted=$(grep -v '^[[:space:]]*\(#\|$\)' "$list" | sort -u)
  installed=$(pkg list-installed 2>/dev/null | tail -n +2 | cut -d/ -f1 | sort -u)
  missing=$(comm -23 <(printf '%s\n' "$wanted") <(printf '%s\n' "$installed"))

  if [ -z "$missing" ]; then
    skip "all $(printf '%s\n' "$wanted" | wc -l) packages already installed"
    return 0
  fi

  do_ "installing $(printf '%s\n' "$missing" | wc -l) missing packages"
  # shellcheck disable=SC2086
  pkg install -y $missing
  ok "packages synced"
}
