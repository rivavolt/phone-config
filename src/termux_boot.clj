(ns termux-boot
  "Boot hooks actually fire: the Termux:Boot APK must be present (Play Protect
  blocks its plain adb install, so onboard handles it with the verifier
  toggled — a manual-only step here), and both Termux apps must be doze-exempt
  or Android defers the hooks indefinitely."
  (:require [engine :refer [defstep]]
            [transport :refer [adb ssh-ok?]]
            [clojure.string :as str]))

(defstep :boot-apk "Termux:Boot APK installed — run: phone onboard <serial>"
  :check (ssh-ok? "pm path com.termux.boot >/dev/null 2>&1"))

(defstep :doze-whitelist "Termux + Termux:Boot doze-exempt"
  :plane :adb
  :check (when-let [r (adb "shell" "dumpsys" "deviceidle" "whitelist")]
           (every? #(str/includes? (:out r) %) ["com.termux," "com.termux.boot"]))
  :apply! (doseq [pkg ["com.termux" "com.termux.boot"]]
            (adb "shell" "dumpsys" "deviceidle" "whitelist" (str "+" pkg))))
