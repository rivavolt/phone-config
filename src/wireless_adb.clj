(ns wireless-adb
  "adb reachable over the tailnet across reboots. Three pieces: the wireless-
  debugging toggle (persists on its own), the on-device bootstrap that
  rediscovers the randomized wireless-debug port and re-pins adbd to TCP 5555
  (the pin dies on every reboot — and on some devices on USB unplug), and the
  Termux:Boot hook that re-runs it. The bootstrap authenticates with the fleet
  adb client key the phone authorized over USB, so no pairing dialog is ever
  involved; onboard seeds that key."
  (:require [engine :refer [defstep]]
            [transport :refer [adb ssh ssh-ok? files-current? sync-files]]))

(defstep :wireless-debugging "wireless debugging enabled"
  :check (when (adb "shell" "true")
           (= "1" (:out (adb "shell" "settings" "get" "global" "adb_wifi_enabled"))))
  :apply! (adb "shell" "settings" "put" "global" "adb_wifi_enabled" "1"))

(def payload [["termux-adb-bootstrap" "$PREFIX/bin/termux-adb-bootstrap" "755"]])

(defstep :adb-bootstrap "termux-adb-bootstrap current"
  :check (files-current? payload)
  :apply! (sync-files payload))

(defstep :adb-boot-hook "bootstrap re-runs at every boot"
  :check (or (not (ssh-ok? "test -f ~/.android/adbkey"))
             (ssh-ok? "test -x ~/.termux/boot/10-adb-bootstrap"))
  :apply! (ssh (str "mkdir -p ~/.termux/boot; "
                    "printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/sh' "
                    "'termux-wake-lock 2>/dev/null || true' 'termux-adb-bootstrap' "
                    "'termux-wake-unlock 2>/dev/null || true' > ~/.termux/boot/10-adb-bootstrap; "
                    "chmod 755 ~/.termux/boot/10-adb-bootstrap")))
