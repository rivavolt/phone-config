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
  "Install an APK over adb with Play Protect's adb-install verifier off for the
  duration. Every APK here is a debug build, which the verifier refuses on a
  stock ROM; the flag is deleted again right after, so a device that would have
  accepted the install anyway is left exactly as it was."
  [a apk]
  (a "shell" "settings" "put" "global" "verifier_verify_adb_installs" "0")
  (try (a "install" "-r" apk)
       (finally (a "shell" "settings" "delete" "global" "verifier_verify_adb_installs"))))

(defn- installed? [a pkg] (zero? (:exit (a "shell" "pm" "path" pkg))))

(defn- ensure-apk [a pkg gh-repo pattern]
  (when-not (installed? a pkg)
    (println "== installing" pkg)
    (adb-install a (gh-release-apk gh-repo pattern))
    ;; a refused install is not always a non-zero adb exit, and the only symptom
    ;; downstream is a bootstrap wait that never ends — so assert the package landed
    (when-not (installed? a pkg)
      (throw (ex-info (str pkg " did not install") {:pkg pkg :repo gh-repo})))))

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
  ;; probe the Termux prefix specifically: LineageOS ships a vestigial
  ;; /product/bin/sshd that `command -v` finds but that cannot even link
  (run-as "[ -x $PREFIX/bin/sshd ] || pkg install -y openssh python")
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
    (ensure-apk a "com.termux.boot" "termux/termux-boot" "*.apk")
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
