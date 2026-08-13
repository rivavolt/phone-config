(ns sshd
  "The phone is reachable over ssh: host keys, the port-8022 listener config,
  the fleet's authorized keys, sshd supervised by termux-services, and the
  Termux:Boot hook that brings it up after a reboot."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? files-current? sync-files]]
            [nixos-config]))

(defstep :ssh-host-keys "host keys present"
  :check (ssh-ok? "ls $PREFIX/etc/ssh/ssh_host_*_key >/dev/null 2>&1")
  :apply! (ssh "ssh-keygen -A"))

(defn payload []
  [[@nixos-config/authorized-keys-file "~/.ssh/authorized_keys"              "600"]
   ["sshd_config.d/listen.conf" "$PREFIX/etc/ssh/sshd_config.d/listen.conf" "644"]
   [".termux/boot/start-sshd"   "~/.termux/boot/start-sshd"                 "755"]])

(defstep :sshd-files "authorized_keys + listener config + boot hook current"
  :check (do (ssh "mkdir -p ~/.ssh ~/.termux/boot $PREFIX/etc/ssh/sshd_config.d; chmod 700 ~/.ssh")
             (files-current? (payload)))
  :apply! (sync-files (payload)))

(defstep :sshd-service "sshd supervised by termux-services"
  :check (ssh-ok? "SVDIR=$PREFIX/var/service sv status sshd 2>/dev/null | grep -q '^run:'")
  :apply! (ssh (str "rm -f $PREFIX/var/service/sshd/down; "
                    "pgrep -x runsvdir >/dev/null || setsid sh -c 'SVDIR=$PREFIX/var/service exec runsvdir $PREFIX/var/service' >/dev/null 2>&1 & "
                    "sleep 2; SVDIR=$PREFIX/var/service sv up sshd")))
