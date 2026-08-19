(ns kdeconnect
  "KDE Connect's notification-less clipboard auto-sync. Since Android 10 a
  background app can't read the clipboard, so stock KDE Connect needs a tap on
  its notification to share it; with READ_LOGS granted the app instead tails
  logcat for the system's own denial line and reads the clip through an
  invisible SYSTEM_ALERT_WINDOW activity. Both grants are adb-only (no app UI
  offers them) and survive reboots and app updates but not a reinstall or a
  new device, hence this step. A phone without KDE Connect installed is
  satisfied, not drifted."
  (:require [engine :refer [defstep]]
            [transport :refer [adb]]
            [clojure.string :as str]))

(def ^:private pkg "org.kde.kdeconnect_tp")

(defstep :kdeconnect-clipboard "KDE Connect clipboard auto-sync grants"
  :plane :adb
  :check (if (adb "shell" "pm" "path" pkg)
           (and (adb "shell" (str "dumpsys package " pkg
                                  " | grep -q 'android.permission.READ_LOGS: granted=true'"))
                (some-> (adb "shell" "appops" "get" pkg "SYSTEM_ALERT_WINDOW")
                        :out (str/includes? "allow")))
           true)
  :apply! (do (adb "shell" "pm" "grant" pkg "android.permission.READ_LOGS")
              (adb "shell" "appops" "set" pkg "SYSTEM_ALERT_WINDOW" "allow")
              ;; the grants only take effect at process start, and the logcat
              ;; watcher thread they enable spawns once per process lifetime
              (adb "shell" "am" "force-stop" pkg)))
