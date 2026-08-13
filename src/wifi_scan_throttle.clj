(ns wifi-scan-throttle
  "Wi-Fi scan throttling off, so the phone re-finds networks (and the tailnet
  with them) quickly when moving between them, at a mild battery cost."
  (:require [transport :refer [settings-step]]))

(settings-step :wifi-scan-throttle "wi-fi scan throttling off"
               [["global" "wifi_scan_throttle_enabled" "0"]])
