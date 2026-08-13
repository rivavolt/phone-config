(ns onboard
  "First contact with a new phone over USB adb, before ssh exists: Termux +
  Termux:Boot APKs, the fleet adb client key, wireless debugging, adbd pinned
  to TCP 5555. Everything runs through `run-as com.termux` (Termux debug
  builds are debuggable), so no phone UI beyond the Tailscale sign-in."
  (:require [transport :refer [repo sh-out]]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- b64 [path] (str/trim (:out (sh-out "base64" "-w0" path))))

(defn run
  [serial]
  (let [a (fn [& args] (apply sh-out "adb" "-s" serial args))
        run-as (fn [cmd] (a "shell" (str "run-as com.termux sh -c '"
                                         "export PREFIX=/data/data/com.termux/files/usr;"
                                         "export HOME=/data/data/com.termux/files/home;"
                                         "export PATH=$PREFIX/bin:$PATH;"
                                         "export LD_LIBRARY_PATH=$PREFIX/lib; " cmd "'")))]
    (println "== device" (:out (a "shell" "getprop" "ro.product.model")))
    (when-not (str/includes? (:out (a "shell" "ip" "-4" "addr")) "inet 100.")
      (println "Tailscale not connected. Sign in against https://hs.avolt.net, then:")
      (println "  adb logcat -d | grep -o 'hs.avolt.net/register/[A-Za-z0-9]*'")
      (println "  ssh amp -- sudo headscale nodes register -u andrei -k <key>")
      (System/exit 1))
    (when-not (zero? (:exit (a "shell" "pm" "path" "com.termux")))
      (println "== installing Termux APK")
      (let [tmp (str (fs/create-temp-dir))]
        (p/shell "gh" "release" "download" "--repo" "termux/termux-app"
                 "--pattern" "*arm64-v8a.apk" "-D" tmp)
        (a "install" "-r" (str (first (fs/glob tmp "*.apk"))))))
    (when-not (zero? (:exit (a "shell" "pm" "path" "com.termux.boot")))
      ;; the Boot APK fails Play Protect verification over adb — toggle the
      ;; verifier for just this install
      (println "== installing Termux:Boot APK")
      (let [tmp (str (fs/create-temp-dir))]
        (p/shell "gh" "release" "download" "--repo" "termux/termux-boot"
                 "--pattern" "*.apk" "-D" tmp)
        (a "shell" "settings" "put" "global" "verifier_verify_adb_installs" "0")
        (a "install" "-r" (str (first (fs/glob tmp "*.apk"))))
        (a "shell" "settings" "delete" "global" "verifier_verify_adb_installs")))
    (a "shell" "am" "start" "-n" "com.termux/.app.TermuxActivity")
    (println "== waiting for Termux bootstrap")
    (loop []
      (when-not (try (> (parse-long (str/trim (:out (run-as "ls $PREFIX/bin | wc -l")))) 100)
                     (catch Exception _ false))
        (Thread/sleep 3000)
        (recur)))
    ;; the phone authorized the fleet adb key over USB, so the wireless-debug
    ;; connect authenticates with it — no pairing dialog, ever
    (println "== seeding fleet adb key")
    (run-as (str "mkdir -p $HOME/.android;"
                 "echo " (b64 (str (fs/expand-home "~/.android/adbkey"))) " | base64 -d > $HOME/.android/adbkey;"
                 "echo " (b64 (str (fs/expand-home "~/.android/adbkey.pub"))) " | base64 -d > $HOME/.android/adbkey.pub;"
                 "chmod 600 $HOME/.android/adbkey"))
    (println "== ssh + core packages")
    (run-as "command -v sshd >/dev/null || pkg install -y openssh python")
    (run-as "mkdir -p $HOME/.ssh; chmod 700 $HOME/.ssh")
    (run-as (str "echo " (b64 (str repo "/authorized_keys"))
                 " | base64 -d > $HOME/.ssh/authorized_keys; chmod 600 $HOME/.ssh/authorized_keys"))
    (run-as "pgrep -x sshd >/dev/null || sshd")
    (a "shell" "am" "start" "-n" "com.termux.boot/.BootActivity")
    ;; last: `adb tcpip` restarts adbd, killing anything started via run-as
    ;; over this same transport — sshd included
    (println "== wireless debugging + fixed port")
    (a "shell" "settings" "put" "global" "adb_wifi_enabled" "1")
    (a "tcpip" "5555")
    (println "== onboard done — now: phone apply <device>")))
