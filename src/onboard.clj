(ns onboard
  "First contact with a new phone over USB adb, before ssh exists. Everything
  runs through `run-as com.termux` (Termux debug builds are debuggable), so
  the only phone UI is the Tailscale sign-in tap — even headscale registration
  is scraped from logcat and enrolled via amp."
  (:require [transport :refer [sh-out]]
            [nixos-config]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- b64 [path]
  (.encodeToString (java.util.Base64/getEncoder) (fs/read-all-bytes path)))

(defn- gh-release-apk
  "Download a release APK; returns its local path."
  [gh-repo pattern]
  (let [tmp (str (fs/create-temp-dir))]
    (p/shell "gh" "release" "download" "--repo" gh-repo "--pattern" pattern "-D" tmp)
    (str (first (fs/glob tmp "*.apk")))))

(defn- adb-install
  "Install an APK over adb. Play Protect blocks some APKs (Termux:Boot) over
  plain adb install, so the verifier can be toggled for just that install."
  [a apk & {:keys [verifier-off]}]
  (when verifier-off (a "shell" "settings" "put" "global" "verifier_verify_adb_installs" "0"))
  (a "install" "-r" apk)
  (when verifier-off (a "shell" "settings" "delete" "global" "verifier_verify_adb_installs")))

(defn- ensure-apk [a pkg gh-repo pattern & opts]
  (when-not (zero? (:exit (a "shell" "pm" "path" pkg)))
    (println "== installing" pkg)
    (apply adb-install a (gh-release-apk gh-repo pattern) opts)))

(defn- tailnet-up? [a] (str/includes? (:out (a "shell" "ip" "-4" "addr")) "inet 100."))

(defn- ensure-tailnet
  "Wait for the Tailscale sign-in (the one UI step), scrape the register key
  from logcat, enroll the node via amp, then wait for the tailnet address."
  [a]
  (when-not (tailnet-up? a)
    (println "== open Tailscale on the phone and sign in against https://hs.avolt.net")
    (loop []
      (if-let [k (re-find #"hs\.avolt\.net/register/([A-Za-z0-9]+)"
                          (:out (a "logcat" "-d" "-t" "4000")))]
        (do (println "== registering node via amp")
            (sh-out "ssh" "amp" (str "sudo headscale nodes register --user andrei --key " (second k))))
        (do (Thread/sleep 3000) (recur))))
    (println "== waiting for the tailnet address")
    (loop []
      (when-not (tailnet-up? a)
        (Thread/sleep 3000)
        (recur)))
    (println "== registered — name and tag it: ssh amp -- sudo headscale nodes list")))

(defn- wait-termux-bootstrap [run-as]
  (println "== waiting for Termux bootstrap")
  (loop []
    (when-not (try (> (parse-long (:out (run-as "ls $PREFIX/bin | wc -l"))) 100)
                   (catch Exception _ false))
      (Thread/sleep 3000)
      (recur))))

(defn- seed-adb-key
  "The phone authorized the fleet adb key over USB, so the wireless-debug
  connect authenticates with it — no pairing dialog, ever."
  [run-as]
  (println "== seeding fleet adb key")
  (run-as (str "mkdir -p $HOME/.android;"
               "echo " (b64 (str (fs/expand-home "~/.android/adbkey"))) " | base64 -d > $HOME/.android/adbkey;"
               "echo " (b64 (str (fs/expand-home "~/.android/adbkey.pub"))) " | base64 -d > $HOME/.android/adbkey.pub;"
               "chmod 600 $HOME/.android/adbkey")))

(defn- seed-ssh
  "Core packages + the rendered fleet keys, and a first sshd so `phone apply`
  can take over from here."
  [run-as]
  (println "== ssh + core packages")
  (run-as "command -v sshd >/dev/null || pkg install -y openssh python")
  (run-as "mkdir -p $HOME/.ssh; chmod 700 $HOME/.ssh")
  (run-as (str "echo " (b64 @nixos-config/authorized-keys-file)
               " | base64 -d > $HOME/.ssh/authorized_keys; chmod 600 $HOME/.ssh/authorized_keys"))
  (run-as "pgrep -x sshd >/dev/null || sshd"))

(defn run
  [serial]
  (let [a (fn [& args] (apply sh-out "adb" "-s" serial args))
        run-as (fn [cmd] (a "shell" (str "run-as com.termux sh -c '"
                                         "export PREFIX=/data/data/com.termux/files/usr;"
                                         "export HOME=/data/data/com.termux/files/home;"
                                         "export PATH=$PREFIX/bin:$PATH;"
                                         "export LD_LIBRARY_PATH=$PREFIX/lib; " cmd "'")))]
    (println "== device" (:out (a "shell" "getprop" "ro.product.model")))
    (ensure-tailnet a)
    (ensure-apk a "com.termux" "termux/termux-app" "*arm64-v8a.apk")
    (ensure-apk a "com.termux.boot" "termux/termux-boot" "*.apk" :verifier-off true)
    (a "shell" "am" "start" "-n" "com.termux/.app.TermuxActivity")
    (wait-termux-bootstrap run-as)
    (seed-adb-key run-as)
    (seed-ssh run-as)
    (a "shell" "am" "start" "-n" "com.termux.boot/.BootActivity")
    ;; last: `adb tcpip` restarts adbd, killing anything started via run-as
    ;; over this same transport — sshd included
    (println "== wireless debugging + fixed port")
    (a "shell" "settings" "put" "global" "adb_wifi_enabled" "1")
    (a "tcpip" "5555")
    (println "== onboard done — add the device to phone's table, then: phone apply <device>")))
