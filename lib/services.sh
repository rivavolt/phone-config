#!/usr/bin/env bash
# termux-services (runit). Idempotent enable + reload-or-start.
#
# SVDIR/LOGDIR are set by $PREFIX/etc/profile.d/start-services.sh but only for
# interactive logins. This script may run under `bash setup` over a
# non-interactive ssh exec, so we export them ourselves before any sv call.

export SVDIR="$PREFIX/var/service"
export LOGDIR="$PREFIX/var/log"

service_enable() {
  local name="$1"
  command -v sv-enable >/dev/null || die "termux-services not installed"

  local link="$PREFIX/var/service/$name"
  if [ -L "$link" ] || [ -d "$link" ]; then
    skip "service $name already enabled"
  else
    do_ "sv-enable $name"
    sv-enable "$name"
    ok "service $name enabled"
  fi
}

service_up() {
  local name="$1"
  if sv status "$name" 2>/dev/null | grep -q '^run:'; then
    do_ "sv hup $name (reload, already running)"
    sv hup "$name" >/dev/null || sv restart "$name" >/dev/null
    ok "service $name reloaded"
  else
    do_ "sv up $name"
    sv up "$name"
    ok "service $name started"
  fi
}

boot_script() {
  local src="$1" name="$2"
  [ -f "$src" ] || die "boot script source missing: $src"

  local dir="$HOME/.termux/boot"
  mkdir -p "$dir"
  local dst="$dir/$name"

  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    skip "boot script $name up to date"
  else
    do_ "installing boot script $name"
    install -m 755 "$src" "$dst"
    ok "boot script $name written"
  fi
}
