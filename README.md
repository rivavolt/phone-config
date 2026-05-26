# termux-setup

Idempotent Termux bootstrap. Vendored pubkeys + sshd config + package list,
re-runnable any time.

## Layout

```
.
├── setup                       idempotent entrypoint
├── packages.txt                pkg names, one per line (termux-main + tur-repo)
├── authorized_keys             pubkeys for ~/.ssh/authorized_keys (generated)
├── sshd_config.d/listen.conf   drop-in for $PREFIX/etc/ssh/sshd_config.d/
├── sync-keys                   regen authorized_keys from nixos-config
├── sync-packages               diff installed-on-phone vs packages.txt
├── lib/                        bash modules sourced by setup
│   └── manual-install.sh       binary-fetched tools missing from any repo
│                               (doctl, gcloud, pnpm)
└── .termux/boot/start-sshd     installed into ~/.termux/boot/ for Termux:Boot
```

## First-time install

On the phone, in Termux:

```
pkg install -y git
git clone https://github.com/avolt/termux-setup.git
cd termux-setup
bash setup
```

That installs every package in `packages.txt`, switches the login shell to zsh,
generates host keys, writes `~/.ssh/authorized_keys` from the vendored file,
drops `listen.conf` into `sshd_config.d/`, enables + starts sshd via
termux-services, and installs the boot script.

Re-open Termux for the zsh change to take effect.

## Re-running

`bash setup` is safe to run any time. Every step inspects state before
mutating — packages already installed are skipped, an enabled service stays
enabled, an up-to-date config file isn't touched.

The verify pass at the end checks: sshd running, authorized_keys non-empty,
port 8022 configured, host keys present. Failure is reported and exits non-zero.

## Adding a new device pubkey

1. Add it to `~/dev/nixos-config/modules/shared/ssh-keys.nix` under `userKeys`.
2. On a dev box (riva/watts/mac): `cd ~/dev/termux-setup && ./sync-keys`.
3. Commit + push. On the phone: `git pull && bash setup`.

`sync-keys` reads `userKeys` from `ssh-keys.nix` via awk (no nix evaluator
required) and rewrites `authorized_keys`. The phone key itself is excluded.

## Boot persistence

`bash setup` installs `~/.termux/boot/start-sshd`, but that only fires on boot
if you also install the **Termux:Boot** APK (F-Droid / GitHub releases). APKs
can't be installed from inside Termux, hence the manual step.

## Termux vs NixOS — what you give up

- No atomic upgrades. `pkg update` mutates in place; if a package upgrade
  breaks something, there's no generation to roll back to.
- No version pinning. `packages.txt` lists names only — you always get
  whatever's current in the Termux repo.
- No declarative service supervision beyond runit's symlinks; service config
  itself (sshd_config.d, etc.) is the only declarative surface.
- No reproducible userland — `$HOME` mutations from interactive use are not
  tracked.

Within those limits, the setup is structured to: (a) live in a repo so changes
are auditable, (b) be safely re-runnable so drift is recoverable by re-applying,
(c) source pubkeys from the same `ssh-keys.nix` that nixos hosts use, so a new
device propagates to phone with one edit + `sync-keys` + push.
