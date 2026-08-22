(ns process-survival
  "Termux (and its supervised sshd + adb-bootstrap boot hook) must survive in the
  background, or a reboot silently drops the phone off the fleet: Android's
  phantom-process killer reaps Termux's child processes, and adbd loses its fixed
  port. The phantom-process monitor is disabled and adbd's TCP port is persisted
  so the management plane comes back on its own after a reboot rather than needing
  a cable. Learned when a pixel3 reboot killed Termux and stranded the phone with
  only Tailscale (a system VPN) still up.

  The plane also dies WITHOUT a reboot: Android kills the whole Termux app under
  memory pressure mid-uptime, taking runsvdir and every supervised daemon with
  it, and the per-service boot hooks never fire again until the next boot. So the
  bring-up is one idempotent script, termux-plane-up, run by three callers — the
  boot hook here (reboot), the service steps over ssh (phone apply), and a host
  over adb through RunCommandService when ssh is down (see <device>-adb
  restart-ssh). It holds the foreground wake-lock, which is what keeps the app
  from being killed in the first place."
  (:require [engine :refer [defstep]]
            [transport :refer [repo-file files-step settings-step adb]]))

(settings-step :phantom-killer-off "phantom-process monitor disabled"
               [["global" "settings_enable_monitor_phantom_procs" "false"]])

;; device_config is a separate store from `settings`, and it enforces the cap
;; independently, so raise it there too. adbd's fixed port is a read-only prop
;; set through a persist. key, which then survives reboots on its own.
(defstep :adbd-persist-port "adbd fixed port + phantom cap persist across reboot"
  :plane :adb
  :check (and (adb "shell" "getprop persist.adb.tcp.port | grep -q 5555")
              (adb "shell" "device_config get activity_manager max_phantom_processes | grep -q 2147483647"))
  :apply! (do (adb "shell" "setprop persist.adb.tcp.port 5555")
              (adb "shell" "device_config put activity_manager max_phantom_processes 2147483647")
              (adb "shell" "device_config set_sync_disabled_for_tests persistent")))

(files-step :plane-up "management-plane bring-up script + boot hook current"
            [[(repo-file "termux-plane-up")            "$PREFIX/bin/termux-plane-up"      "755"]
             [(repo-file ".termux/boot/20-plane-up")   "~/.termux/boot/20-plane-up"       "755"]])
