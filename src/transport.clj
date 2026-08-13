(ns transport
  "All I/O to a device: ssh into Termux (port 8022), adb against the tailnet
  adbd, and content-addressed file push. The target device is bound once per
  run via *dev* {:ssh user@host :adb host:port}."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:dynamic *dev* nil)

(def repo
  (str (babashka.fs/parent (babashka.fs/parent (babashka.fs/canonicalize *file*)))))

(defn sh-out [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (assoc r :out (str/trim (:out r)))))

(defn ssh
  "Run a command in the device's Termux; returns {:exit :out}."
  [cmd]
  (sh-out "ssh" "-p" "8022" "-o" "ConnectTimeout=10"
          "-o" "StrictHostKeyChecking=accept-new" (:ssh *dev*)
          (str ". $PREFIX/etc/profile.d/termux-exec-mode.sh 2>/dev/null; " cmd)))

(defn ssh-ok? [cmd] (zero? (:exit (ssh cmd))))

(defn adb
  "Run adb against the device's tailnet adbd; nil when unreachable. Bounded by
  `timeout` — a dozing phone lets the TCP connect hang for minutes otherwise."
  [& args]
  (let [t (:adb *dev*)]
    (sh-out "timeout" "12" "adb" "connect" t)
    (let [r (apply sh-out "timeout" "20" "adb" "-s" t args)]
      (when (zero? (:exit r)) r))))

(defn- local-path [src]
  (if (str/starts-with? src "/") src (str repo "/" src)))

(defn file-current?
  "Does dest on the device have the same content as src (repo-relative, or an
  absolute path for rendered files)?"
  [src dest]
  (let [local (first (str/split (:out (sh-out "md5sum" (local-path src))) #" "))
        remote (:out (ssh (str "md5sum " dest " 2>/dev/null | cut -d' ' -f1")))]
    (= local remote)))

(defn push-file [src dest mode]
  (p/shell "scp" "-P" "8022" "-q" "-o" "StrictHostKeyChecking=accept-new"
           (local-path src) (str (:ssh *dev*) ":" dest))
  (ssh (str "chmod " mode " " dest)))

(defn files-current?
  "Are all [src dest mode] rows current on the device?"
  [rows]
  (every? (fn [[src dest _]] (file-current? src dest)) rows))

(defn sync-files
  "Push every stale [src dest mode] row."
  [rows]
  (doseq [[src dest mode] rows
          :when (not (file-current? src dest))]
    (push-file src dest mode)))
