#!/usr/bin/env bash
# Installs tools that are NOT in any Termux apt repo (termux-main, tur-repo).
# Each installer is idempotent — re-running is a no-op if the binary is current.
#
# Kept separate from packages.txt because pkg can't see them and would error
# with "Unable to locate" if they were lumped in.

manual_install_all() {
  manual_doctl
  manual_gcloud
  manual_pnpm
}

# DigitalOcean CLI — official release binary from GitHub, aarch64 only.
manual_doctl() {
  if command -v doctl >/dev/null; then
    skip "doctl already installed ($(doctl version 2>/dev/null | head -1))"
    return 0
  fi
  do_ "fetching latest doctl release for linux-arm64"
  local tag tarball url tmp
  tag=$(curl -fsSL https://api.github.com/repos/digitalocean/doctl/releases/latest \
          | gojq -r .tag_name)
  [ -n "$tag" ] || die "doctl: failed to resolve latest tag"
  tarball="doctl-${tag#v}-linux-arm64.tar.gz"
  url="https://github.com/digitalocean/doctl/releases/download/${tag}/${tarball}"
  tmp=$(mktemp -d)
  curl -fsSL "$url" -o "$tmp/$tarball"
  tar -xzf "$tmp/$tarball" -C "$tmp" doctl
  install -m 755 "$tmp/doctl" "$PREFIX/bin/doctl"
  rm -rf "$tmp"
  ok "doctl $tag installed"
}

# Google Cloud SDK — fetch the arm64 tarball directly and symlink entry-point
# scripts onto PATH. The upstream `install.sh` / `sdk.cloud.google.com` bash
# installer hard-requires `which`, which Termux doesn't ship, so we skip it.
manual_gcloud() {
  local home_dir="$HOME/google-cloud-sdk"
  if [ -x "$home_dir/bin/gcloud" ] && command -v gcloud >/dev/null; then
    skip "gcloud already installed ($($home_dir/bin/gcloud --version 2>/dev/null | head -1))"
    return 0
  fi
  do_ "downloading google-cloud-cli-linux-arm.tar.gz"
  local url="https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-arm.tar.gz"
  local tmp
  tmp=$(mktemp -d)
  curl -fsSL "$url" -o "$tmp/gcloud.tar.gz"
  rm -rf "$home_dir"
  tar -xzf "$tmp/gcloud.tar.gz" -C "$HOME"
  rm -rf "$tmp"
  for bin in gcloud gsutil bq; do
    [ -x "$home_dir/bin/$bin" ] || continue
    ln -sf "$home_dir/bin/$bin" "$PREFIX/bin/$bin"
  done
  ok "gcloud installed under $home_dir"
}

# pnpm — installed via npm (nodejs must be present, packages.sh runs first).
manual_pnpm() {
  if command -v pnpm >/dev/null; then
    skip "pnpm already installed ($(pnpm --version 2>/dev/null))"
    return 0
  fi
  command -v npm >/dev/null || die "pnpm: npm missing (nodejs package not installed yet)"
  do_ "npm install -g pnpm"
  npm install -g pnpm
  ok "pnpm installed"
}
