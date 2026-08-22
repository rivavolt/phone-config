(ns sshd
  "The phone is reachable over ssh: host keys, the port-8022 listener config,
  the fleet's authorized keys, and sshd supervised by termux-services. Bringing
  the supervision tree up (at boot and on demand) belongs to termux-plane-up in
  process-survival, so this file owns sshd's identity and config, not its
  lifecycle."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? repo-file files-step require-pkgs!]]
            [nixos-config]))

;; the package this policy is built on
(require-pkgs! "termux-services")

(defstep :ssh-host-keys "host keys present"
  :check (ssh-ok? "ls $PREFIX/etc/ssh/ssh_host_*_key >/dev/null 2>&1")
  :apply! (ssh "ssh-keygen -A"))

(files-step :sshd-files "authorized_keys + listener config current"
            [[nixos-config/authorized-keys-file      "~/.ssh/authorized_keys"                    "600"]
             [(repo-file "sshd_config.d/listen.conf") "$PREFIX/etc/ssh/sshd_config.d/listen.conf" "644"]]
            :after "chmod 700 ~/.ssh")

(defstep :sshd-service "sshd supervised by termux-services"
  :check (ssh-ok? "SVDIR=$PREFIX/var/service sv status sshd 2>/dev/null | grep -q '^run:'")
  :apply! (ssh "rm -f $PREFIX/var/service/sshd/down; termux-plane-up"))
