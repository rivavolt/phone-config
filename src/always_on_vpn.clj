(ns always-on-vpn
  "The tailnet survives reboots: Android's always-on VPN pinned to Tailscale.
  Lockdown stays 0 deliberately — with it on, Android drops ALL traffic
  whenever the VPN is down, so an expired node key or a Tailscale crash
  strands the phone with no network at all, including the adb/ssh paths
  needed to recover it. Briefly losing the tailnet is the lesser failure."
  (:require [engine :refer [step!]]
            [transport :refer [settings-step]]))

(step! (settings-step :always-on-vpn "always-on VPN = Tailscale, lockdown off"
                      [["secure" "always_on_vpn_app" "com.tailscale.ipn"]
                       ["secure" "always_on_vpn_lockdown" "0"]]))
