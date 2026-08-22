# phone-config

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
phone                       entry: device roster + CLI dispatch (ssh details come from
                            the workstation's rendered ~/.ssh/config, not from here)
src/engine.clj              step registry + converge loop (check → apply → re-check)
src/transport.clj           ssh / adb / content-addressed file push
src/android16_exec.clj      workaround with a sunset — delete the file when upstream fixes it
src/always_on_vpn.clj       policy: the tailnet survives reboots (lockdown OFF, with why)
src/airplane_radios.clj     policy: airplane mode spares wifi + bluetooth
src/fast_animations.clj     policy: animation scales at half duration
src/wifi_scan_throttle.clj  policy: wi-fi scan throttling off
src/storage.clj             policy: Termux reads shared storage (appop + ~/storage farm)
src/wireless_adb.clj        policy: adb reachable across reboots (toggle + bootstrap + hook)
src/termux_boot.clj         policy: boot hooks actually fire (Boot APK + doze exemptions)
src/process_survival.clj    policy: the plane survives reboot AND mid-uptime app kills
                            (phantom-killer off, adbd port persist, termux-plane-up + hook)
src/sshd.clj                policy: reachable over ssh (keys, config, supervision)
src/socks_proxy.clj         policy: the fleet can egress through the phone (converges to
                            down; `<device>-proxy on` runs it)
src/userland.clj            policy: the dev environment (packages, zsh, ui config, doctl/gcloud/pnpm)
src/nixos_config.clj        the nixos-config seam: authorized_keys + ssh_config rendered
                            fresh at apply time — never vendored
src/onboard.clj             USB first-contact flow (runs before ssh exists)
sshd_config.d/listen.conf   drop-in for $PREFIX/etc/ssh/sshd_config.d/
termux-adb-bootstrap        re-pins adbd to TCP 5555 (runs on-device at boot)
termux-plane-up             brings the plane up (wake-lock + runsvdir + sv up); one
                            script, three callers: boot hook, ssh, adb/RunCommandService
.termux/boot/20-plane-up    Termux:Boot hook: runs termux-plane-up
```

Files group by the policy that carries a rationale, never by mechanism —
`always_on_vpn.clj` answers "why is lockdown off" by existing. Registration
order is execution order, declared once in `phone`. Workarounds get their own
file named for the specific problem (`android16_exec.clj`); the filename
carries the sunset, and deleting the file is the whole change when it arrives.
Generated files are rendered from nixos-config at apply time rather than
vendored: a committed render is a cache with no invalidation (the old
sync-keys shipped a stale key set for months before this rewrite caught it).

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

Two management planes, each recovering the other while the phone stays up. adb
(adbd pinned to TCP 5555 by `persist.adb.tcp.port`) is the more durable one: it
survives the Termux app being killed, so it heals ssh. ssh rides the Termux app,
so it dies whenever the app does. When adb is down (5555 lost) but ssh is up,
recover adb via `ssh -p 8022 <device> termux-adb-bootstrap`. When ssh is down but
adb is up (the common case — Android killed the app mid-uptime), recover ssh via
`<device>-adb restart-ssh`, which fires termux-plane-up through Termux's
RunCommandService.

A REBOOT of a PIN-locked phone needs care, but on a ROOTED phone it is NOT a
one-way trip: given adb access you can unlock it remotely, without ever touching
the keyguard or the SIM prompt —

    adb ... shell su -c 'locksettings verify --old <DEVICE_PIN>'
      → "Lock credential verified successfully"

goes through LockSettingsService and unlocks CE storage (BFU → AFU), spending no
SIM attempt and needing no `input`/`wm dismiss-keyguard`. Use `verify`
(non-destructive); NEVER `clear`, which destroys the credential. A wrong device
PIN costs only a timed backoff — unlike the SIM PIN, which PUK-locks after ~3
tries, so never inject a device PIN into the SIM prompt (`getprop gsm.sim.state`
= PIN_REQUIRED/PUK_REQUIRED/LOADED distinguishes the two; `mDreamingLockscreen`
cannot). Measured on the Pixel 3 after a real reboot: `locksettings verify`
unlocked CE, then tailnet returned at t+10s and sshd (Termux:Boot → 20-plane-up)
at t+50s — the whole plane recovered on its own.

The real constraint is narrower: you must REACH adbd during BFU, and BFU Wi-Fi
is intermittent. adbd comes back on 5555 (the persist prop survives) and answers
on the LAN, but the Wi-Fi association drops and returns while the phone is still
BFU (CE-encrypted supplicant config), so the recovery window is flaky, not
absent — adbd was reachable again at 2141s uptime, far past the first ~50s
window, i.e. availability recurs rather than closing permanently. Retry the LAN
address until it answers, then `locksettings verify`. Cellular does NOT help
while the SIM is at its PIN. The durable fix, if wanted, is a DE-stored Wi-Fi
network the phone can join pre-unlock (unverified on this build). Bottom line:
still design supervision that never needs a reboot, but a reboot is recoverable
remotely on this rooted handset — it is not the dead end it first appeared.

Why RunCommandService and not `adb shell su -c sshd`: sshd has to run AS the
Termux user (uid 10286) — its authorized_keys live in that user's CE storage,
and Termux's sshd has no root-to-user mapping, so a root-launched listener
rejects the key. `su 10286` doesn't get you there either: `su <uid>` drops the
supplementary groups down to just that uid, and Android's paranoid-network gate
denies AF_INET to a non-root process without gid 3003 (inet) — the dropped sshd
fails with "Cannot bind any address". RunCommandService runs the command as the
real Termux app, which is the one context that is both uid 10286 AND carries its
native inet group with CE storage unlocked. (The blocker is the missing inet
group, not any SELinux domain — `adb shell` here is root in the `su` domain and
binds fine; a standalone daemon like calld can therefore be launched as root
and self-drop while keeping inet, no RunCommandService needed.)

## Adding a device pubkey

Add a `userKey` to the machine's entry in nixos-config's `flake/machines.nix`,
then `./phone apply <device>` per phone — the render picks it up directly.
Phones' own keys are excluded via their `androidDevice` flag.

## Termux vs NixOS — what you give up

- No atomic upgrades. `pkg update` mutates in place; no generation to roll
  back to.
- No version pinning. Policies declare package names only (`require-pkgs!`) —
  you always get whatever's current in the Termux repo.
- No reproducible userland — `$HOME` mutations from interactive use are not
  tracked.

Within those limits: desired state lives in a repo, converging is one
re-runnable command, and drift is visible (`phone verify`) rather than
discovered.
