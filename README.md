# termux-setup

Idempotent Termux bootstrap. Vendored pubkeys + sshd config + package list,
re-runnable any time.

## Layout

```
.
├── setup                       idempotent entrypoint
├── packages.txt                pkg names, one per line (termux-main + tur-repo)
├── authorized_keys             pubkeys for ~/.ssh/authorized_keys (generated)
├── ssh_config                  ~/.ssh/config (generated — see sync-config)
├── sshd_config.d/listen.conf   drop-in for $PREFIX/etc/ssh/sshd_config.d/
├── sync-config                 regen ssh_config from nixos-config
├── sync-keys                   regen authorized_keys from nixos-config
├── sync-packages               diff installed-on-phone vs packages.txt
├── lib/                        bash modules sourced by setup
│   └── manual-install.sh       binary-fetched tools missing from any repo
│                               (doctl, gcloud, pnpm)
├── onboard                     host-side zero-UI onboarding of a NEW phone over USB adb
├── termux-adb-bootstrap        re-pins adbd to TCP 5555 (vendored from nixos-config)
└── .termux/boot/start-sshd     installed into ~/.termux/boot/ for Termux:Boot
```

## First-time install

Preferred: from a workstation, phone on USB with adb authorized — no phone UI
beyond the Tailscale sign-in:

```
./onboard <adb-serial>
```

`onboard` drives the whole thing over `run-as com.termux` (Termux debug builds
are debuggable): installs the Termux + Termux:Boot APKs from GitHub releases
(disabling the Play Protect adb-install verifier for just the Boot APK),
pins always-on VPN to Tailscale (lockdown off), seeds the fleet adb client key
into Termux's `~/.android` — the phone already authorized it over USB, so the
wireless-debug connect needs NO pairing dialog, ever — clones this repo, runs
`setup`, enables wireless debugging, and pins adbd to TCP 5555.

Manual fallback, on the phone in Termux:

```
pkg install -y git
git clone https://github.com/andreivolt/termux-setup.git
cd termux-setup
bash setup
```

`setup` installs every package in `packages.txt`, switches the login shell to
zsh, generates host keys, writes `~/.ssh/authorized_keys` from the vendored
file, drops `listen.conf` into `sshd_config.d/`, enables + starts sshd via
termux-services, installs the boot scripts (sshd + the adb 5555 bootstrap),
and reinstalls the uv tools from `~/.config/uv-tools.txt`.

Ordering trap: `adb tcpip 5555` restarts adbd, which kills anything started
via `run-as` over that same transport — a just-started sshd included. On some
devices (Pixel 8) the 5555 bind also dies on USB unplug, not just reboot;
recovery without a cable is a reboot (Termux:Boot) or
`ssh -p 8022 <phone> termux-adb-bootstrap`.

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

## Updating the ssh client config

`ssh_config` (the phone's `~/.ssh/config`) is generated, not hand-edited. Its
single source of truth is nixos-config's `phone-ssh-config` generator, which
derives the fleet host blocks from `modules/shared/ssh-keys.nix` and folds in the
`Host *` keepalives, the `surface` host, and the tailnet catch-all.

To regenerate after a host change: on a dev box, `cd ~/dev/termux-setup &&
./sync-config`, then commit + push and `git pull && bash setup` on the phone.
`sync-config` shells out to nix (unlike `sync-keys`) because the config layout
lives in the generator. The same generator can also push the file straight to the
phone over tailnet SSH with `phone-ssh-config` (no checkout/pull needed).

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
