(ns android
  "Android-plane desired state, applied over adb — works even before Termux
  exists. Steps skip (as drift) when the device's adbd is unreachable."
  (:require [engine :refer [defstep]]
            [transport :refer [adb]]
            [clojure.string :as str]))

(def settings
  ;; Lockdown stays 0 deliberately: with it on, an expired node key strands the
  ;; phone with no network at all — including the adb path needed to recover it.
  [["secure" "always_on_vpn_app" "com.tailscale.ipn"]
   ["secure" "always_on_vpn_lockdown" "0"]
   ["global" "adb_wifi_enabled" "1"]])

(defstep :android-settings "always-on VPN + wireless debugging"
  :check (when (adb "shell" "true")
           (every? (fn [[ns k v]] (= v (:out (adb "shell" "settings" "get" ns k))))
                   settings))
  :apply! (doseq [[ns k v] settings]
            (adb "shell" "settings" "put" ns k v)))

(defstep :doze-whitelist "Termux + Termux:Boot doze-exempt"
  :check (when-let [r (adb "shell" "dumpsys" "deviceidle" "whitelist")]
           (every? #(str/includes? (:out r) %) ["com.termux," "com.termux.boot"]))
  :apply! (doseq [pkg ["com.termux" "com.termux.boot"]]
            (adb "shell" "dumpsys" "deviceidle" "whitelist" (str "+" pkg))))
