(ns socks-proxy
  "The phone lends its internet connection to the fleet: a SOCKS5 proxy
  (microsocks, supervised by termux-services) bound to the device's tailnet
  address, so any fleet host can egress through whatever network the phone is
  on with `curl --socks5-hostname <device>.tail.avolt.net:1080`.

  Unlike the other supervised services here this one converges to DOWN, not
  up. Running it costs the radio, not the daemon — microsocks itself is an idle
  epoll loop burning no measurable CPU — but a phone that proxies for the fleet
  keeps its modem busy and its battery is the scarce resource, so the runtime
  half belongs to whoever wants the traffic: `<device>-proxy on|off` from a
  workstation flips it. What converges from here is that the service EXISTS,
  correct and ready, which is what a reconciler can meaningfully own.

  The tailnet-only bind is the security boundary — see services/socks/run.

  Egress follows the phone's default network, so the proxy is only interesting
  when that differs from the caller's: on wifi the phone usually shares the
  caller's line and the hop buys nothing, while with wifi off it egresses over
  mobile data. Pinning egress to the modem while wifi is up is not possible
  from here — Android selects routes by fwmark, so it needs either
  ConnectivityManager.bindProcessToNetwork() from a real app or CAP_NET_ADMIN
  to set SO_MARK; a source-address bind does not do it."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? repo-file files-step require-pkgs!]]))

;; the packages this policy is built on
(require-pkgs! "termux-services" "microsocks")

(files-step :socks-files "run script + down flag current"
            [[(repo-file "services/socks/run")  "$PREFIX/var/service/socks/run"  "755"]
             [(repo-file "services/socks/down") "$PREFIX/var/service/socks/down" "644"]])

;; `sv status` answers for a service runsv has picked up, whether or not it is
;; running — which is the state this step owns. Bringing it up is the CLI's job.
;; termux-plane-up starts runsvdir (which then scans in this service dir); it
;; won't start socks itself, since the down flag keeps it out of the bring-up.
(defstep :socks-service "socks proxy registered with termux-services"
  :check (ssh-ok? "SVDIR=$PREFIX/var/service sv status socks >/dev/null 2>&1")
  :apply! (ssh "termux-plane-up; sleep 3; SVDIR=$PREFIX/var/service sv status socks >/dev/null 2>&1"))
