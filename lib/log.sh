#!/usr/bin/env bash
# Tiny logger used by every step. Prefixes lines so re-runs are scannable.

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
ok()  { printf '  ok    %s\n' "$*"; }
skip(){ printf '  skip  %s (already done)\n' "$*"; }
do_() { printf '  do    %s\n' "$*"; }
warn(){ printf '  warn  %s\n' "$*" >&2; }
die() { printf '  fail  %s\n' "$*" >&2; exit 1; }
