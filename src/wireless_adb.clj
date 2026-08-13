(ns wireless-adb
  "adb reachable over the tailnet across reboots. Three pieces: the wireless-
  debugging toggle (persists on its own), the on-device bootstrap that
  rediscovers the randomized wireless-debug port and re-pins adbd to TCP 5555
  (the pin dies on every reboot — and on some devices on USB unplug), and the
  Termux:Boot hook that re-runs it. The bootstrap authenticates with the fleet
  adb client key the phone authorized over USB, so no pairing dialog is ever
  involved; onboard seeds that key."
  (:require [engine :refer [step!]]
            [transport :refer [repo-file files-step settings-step]]))

(step! (settings-step :wireless-debugging "wireless debugging enabled"
                      [["global" "adb_wifi_enabled" "1"]]))

(step! (files-step :adb-bootstrap "bootstrap + boot hook current"
                   #(vector [(repo-file "termux-adb-bootstrap")        "$PREFIX/bin/termux-adb-bootstrap"  "755"]
                            [(repo-file ".termux/boot/10-adb-bootstrap") "~/.termux/boot/10-adb-bootstrap" "755"])))
