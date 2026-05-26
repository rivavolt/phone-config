#!/usr/bin/env bash
# SSH setup: host keys, authorized_keys, and the sshd_config.d drop-in.

ssh_host_keys() {
  local dir="$PREFIX/etc/ssh"
  if compgen -G "$dir/ssh_host_*_key" >/dev/null; then
    skip "ssh host keys present"
    return 0
  fi
  do_ "generating ssh host keys"
  ssh-keygen -A
  ok "host keys generated"
}

ssh_authorized_keys() {
  local src="$1"
  [ -f "$src" ] || die "authorized_keys source missing: $src (run ./sync-keys)"

  mkdir -p "$HOME/.ssh"
  chmod 700 "$HOME/.ssh"

  local dst="$HOME/.ssh/authorized_keys"
  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    skip "authorized_keys up to date"
  else
    do_ "writing $dst"
    install -m 600 "$src" "$dst"
    ok "authorized_keys updated"
  fi
}

ssh_drop_in() {
  local src="$1" name="$2"
  [ -f "$src" ] || die "sshd drop-in source missing: $src"

  local dir="$PREFIX/etc/ssh/sshd_config.d"
  mkdir -p "$dir"
  local dst="$dir/$name"

  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    skip "sshd drop-in $name up to date"
  else
    do_ "installing sshd drop-in $name"
    install -m 644 "$src" "$dst"
    ok "sshd drop-in $name written"
  fi

  # Old auth.conf from the previous setup script — superseded by listen.conf.
  if [ -f "$dir/auth.conf" ] && [ "$name" != "auth.conf" ]; then
    do_ "removing stale sshd drop-in auth.conf"
    rm -f "$dir/auth.conf"
    ok "stale auth.conf removed"
  fi
}
