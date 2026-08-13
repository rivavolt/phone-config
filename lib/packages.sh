#!/usr/bin/env bash
# Install only the packages from packages.txt that aren't already installed.
# Termux has no atomic upgrade — best we can do is diff against pkg list-installed.

packages_apply() {
  local list="$1"
  [ -f "$list" ] || die "packages list missing: $list"

  do_ "pkg update (metadata refresh)"
  pkg update -y >/dev/null

  local wanted installed missing unavailable
  wanted=$(grep -v '^[[:space:]]*\(#\|$\)' "$list" | sort -u)
  installed=$(pkg list-installed 2>/dev/null | tail -n +2 | cut -d/ -f1 | sort -u)
  missing=$(comm -23 <(printf '%s\n' "$wanted") <(printf '%s\n' "$installed"))

  if [ -z "$missing" ]; then
    skip "all $(printf '%s\n' "$wanted" | wc -l) packages already installed"
    return 0
  fi

  # apt aborts the whole batch on one unknown name, and the repos drop or
  # rename packages over time — install only what the repo actually serves and
  # report the rest, rather than letting one dead name sink every package.
  unavailable=$(comm -23 <(printf '%s\n' "$missing") <(apt-cache pkgnames | sort -u))
  if [ -n "$unavailable" ]; then
    warn "not in any repo, skipped: $(printf '%s ' $unavailable)"
    missing=$(comm -12 <(printf '%s\n' "$missing") <(apt-cache pkgnames | sort -u))
    if [ -z "$missing" ]; then
      return 0
    fi
  fi

  do_ "installing $(printf '%s\n' "$missing" | wc -l) missing packages"
  # dpkg conffile prompts (an upgrade shipping a changed openssl.cnf, say) read
  # EOF on a non-tty and abort the batch; answer them non-interactively and
  # keep the installed version.
  # shellcheck disable=SC2086
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef $missing
  ok "packages synced"
}
