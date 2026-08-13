# termux-setup

Desired state for the fleet's Android phones, converged from a workstation.
The phone is a target, not a runtime: `phone` (babashka) drives everything
over ssh (Termux plane, port 8022) and adb (Android plane), checks state
before mutating, and re-running is always safe. The only things living
on-device are the pushed payload files — notably the Termux:Boot hooks,
which must work at boot with no host around.

## Usage

```
./phone apply  [device]    converge (default: pixel8)
./phone verify [device]    check only, exit non-zero on drift
./phone onboard <serial>   first contact over USB adb, then: phone apply
```

## Layout

```
phone                       entry: device table + CLI dispatch
src/engine.clj              step registry + converge loop (check → apply → re-check)
src/transport.clj           ssh / adb / content-addressed file push
src/android16_exec.clj      workaround with a sunset — delete the file when upstream fixes it
src/android.clj             Android-plane state: settings, doze whitelist (over adb)
src/termux.clj              Termux-plane state: packages, shell, sshd, payload files,
                            services, uv tools, binary-fetched tools (over ssh)
src/onboard.clj             USB first-contact flow (runs before ssh exists)
packages.txt                pkg names, one per line (termux-main + tur-repo)
authorized_keys             pubkeys for ~/.ssh/authorized_keys (generated — sync-keys)
ssh_config                  the phone's ~/.ssh/config (generated — sync-config)
sshd_config.d/listen.conf   drop-in for $PREFIX/etc/ssh/sshd_config.d/
termux-adb-bootstrap        re-pins adbd to TCP 5555 (runs on-device at boot)
.termux/boot/start-sshd     Termux:Boot hook: supervised sshd
sync-keys / sync-config     regenerate the two generated files from nixos-config
sync-packages               diff installed-on-phone vs packages.txt
```

Steps live in per-concern files; registration order is execution order,
declared once in `phone`. Workarounds get their own file named for the
specific problem (`android16_exec.clj`), never a shared bucket — the filename
carries the sunset, and deleting the file is the whole change when it arrives.

## Onboarding a new phone

Plug it in with adb authorized, sign Tailscale in against https://hs.avolt.net
(the one UI step), register the node server-side, then:

```
./phone onboard <adb-serial>   # Termux APKs, fleet adb key, wireless debugging
./phone apply <device>         # after adding the device to the table in ./phone
```

`onboard` drives Termux through `run-as com.termux` (debug builds are
debuggable). The Termux:Boot APK fails Play Protect verification over adb, so
the verifier is toggled off for just that install. The fleet adb client key it
seeds is already authorized (it authorized over USB), so the wireless-debug
connect needs no pairing dialog, ever.

Ordering trap, learned the hard way: `adb tcpip 5555` restarts adbd, which
kills anything started via `run-as` over that same transport — a just-started
sshd included. It is deliberately the last thing onboard does. On some devices
(Pixel 8) the 5555 bind also dies on USB unplug, not just reboot; recovery
without a cable is a reboot (Termux:Boot re-runs the bootstrap) or
`ssh -p 8022 <device> termux-adb-bootstrap`.

## Adding a device pubkey

1. Add it to `~/dev/nixos-config/modules/shared/ssh-keys.nix` under `userKeys`.
2. `./sync-keys`, commit, `./phone apply <device>` per phone.

`sync-config` regenerates `ssh_config` the same way (it shells out to nix;
`sync-keys` is plain awk).

## Termux vs NixOS — what you give up

- No atomic upgrades. `pkg update` mutates in place; no generation to roll
  back to.
- No version pinning. `packages.txt` lists names only — you always get
  whatever's current in the Termux repo.
- No reproducible userland — `$HOME` mutations from interactive use are not
  tracked.

Within those limits: desired state lives in a repo, converging is one
re-runnable command, and drift is visible (`phone verify`) rather than
discovered.
