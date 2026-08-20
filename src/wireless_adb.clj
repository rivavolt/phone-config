(ns wireless-adb
  "adb reachable over the tailnet across reboots. Four pieces: the wireless-
  debugging toggle (persists on its own), the fleet adb client key (which the
  phone authorized over USB, so the wireless-debug connect never involves a
  pairing dialog), the on-device bootstrap that rediscovers the randomized
  wireless-debug port and re-pins adbd to TCP 5555 (the pin dies on every
  reboot — and on some devices on USB unplug), and the Termux:Boot hook that
  re-runs it."
  (:require [transport :refer [repo-file files-step settings-step adb]]
            [babashka.fs :as fs]))

;; Android 11+ wireless debugging is the fallback mechanism, not the fleet's:
;; where adbd is pinned to a fixed TCP port the phone is already reachable, and
;; `adb_wifi_enabled` is then a setting the platform holds at 0 no matter who
;; writes it (it tracks a live pairing session, so a bare `settings put` reads
;; back unchanged even as root) — an unguarded row there reports FAIL forever
;; while adb works fine over that very connection.
(defn- adbd-port-pinned? []
  (some-> (adb "shell" "getprop persist.adb.tcp.port") :out clojure.string/trim seq boolean))

(settings-step :wireless-debugging "wireless debugging enabled"
               [["global" "adb_wifi_enabled" "1"]]
               :when #(not (adbd-port-pinned?)))

(files-step :adb-bootstrap "fleet adb key + bootstrap + boot hook current"
            [[(str (fs/expand-home "~/.android/adbkey"))     "~/.android/adbkey"                "600"]
             [(str (fs/expand-home "~/.android/adbkey.pub")) "~/.android/adbkey.pub"            "644"]
             [(repo-file "termux-adb-bootstrap")             "$PREFIX/bin/termux-adb-bootstrap" "755"]
             [(repo-file ".termux/boot/10-adb-bootstrap")    "~/.termux/boot/10-adb-bootstrap"  "755"]])
