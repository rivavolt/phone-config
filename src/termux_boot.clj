(ns termux-boot
  "Boot hooks actually fire: the Termux:Boot APK must be present (it cannot be
  installed from here — Play Protect blocks it over plain adb install, so
  onboard handles it with the verifier toggled), and both Termux apps must be
  doze-exempt or Android defers the hooks indefinitely."
  (:require [engine :refer [defstep]]
            [transport :refer [adb ssh-ok?]]
            [clojure.string :as str]))

(defstep :boot-apk "Termux:Boot APK installed"
  :check (ssh-ok? "pm path com.termux.boot >/dev/null 2>&1")
  :apply! (throw (ex-info "install over adb: phone onboard <serial>" {})))

(defstep :doze-whitelist "Termux + Termux:Boot doze-exempt"
  :check (when-let [r (adb "shell" "dumpsys" "deviceidle" "whitelist")]
           (every? #(str/includes? (:out r) %) ["com.termux," "com.termux.boot"]))
  :apply! (doseq [pkg ["com.termux" "com.termux.boot"]]
            (adb "shell" "dumpsys" "deviceidle" "whitelist" (str "+" pkg))))
