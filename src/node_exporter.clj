(ns node-exporter
  "The phone exports host metrics: the prometheus node_exporter release binary,
  supervised by termux-services, bound to the device's tailnet address so the
  metrics never answer on whatever wifi the phone happens to be on (Termux has
  no firewall to close a 0.0.0.0 bind with). The volt promstack scrapes it over
  the tailnet like the laptops' exporters."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? repo-file files-step require-pkgs!]]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

;; the package this policy is built on
(require-pkgs! "termux-services")

(defstep :node-exporter-bin "node_exporter release binary"
  :check (ssh-ok? "command -v node_exporter >/dev/null")
  :apply! (let [tag (-> (http/get "https://api.github.com/repos/prometheus/node_exporter/releases/latest")
                        :body (json/parse-string true) :tag_name)
                dir (format "node_exporter-%s.linux-arm64" (subs tag 1))]
            (ssh (format (str "cd $TMPDIR && curl -fsSL '%s' -o t.tgz && "
                              "tar -xzf t.tgz %s/node_exporter && "
                              "install -m755 %s/node_exporter $PREFIX/bin/node_exporter && "
                              "rm -rf t.tgz %s")
                         (format "https://github.com/prometheus/node_exporter/releases/download/%s/%s.tar.gz" tag dir)
                         dir dir dir))))

(files-step :node-exporter-files "run script + boot hook current"
            [[(repo-file "services/node_exporter/run")        "$PREFIX/var/service/node_exporter/run"  "755"]
             [(repo-file ".termux/boot/start-node-exporter")  "~/.termux/boot/start-node-exporter"     "755"]])

(defstep :node-exporter-service "node_exporter supervised by termux-services"
  :check (ssh-ok? "SVDIR=$PREFIX/var/service sv status node_exporter 2>/dev/null | grep -q '^run:'")
  :apply! (ssh (str "rm -f $PREFIX/var/service/node_exporter/down; "
                    "pgrep -x runsvdir >/dev/null || setsid sh -c 'SVDIR=$PREFIX/var/service exec runsvdir $PREFIX/var/service' >/dev/null 2>&1 & "
                    "sleep 2; SVDIR=$PREFIX/var/service sv up node_exporter")))
