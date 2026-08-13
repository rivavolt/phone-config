(ns transport
  "All I/O to a device, plus the step builders whose value is transport
  batching (one adb round-trip for every settings row, one ssh for every file
  digest). The target is bound once per run via *dev* {:ssh alias-or-target
  :adb host:port}; the ssh side rides the workstation's rendered ~/.ssh/config
  matchBlocks (user, FQDN, port). File sources are absolute paths or delays
  thereof — use repo-file for files vendored in this repo."
  (:require [engine]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:dynamic *dev* nil)

(def repo (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(defn repo-file [rel] (str repo "/" rel))

(defn sh-out [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (assoc r :out (str/trim (:out r)))))

(def ssh-prelude
  "Shell snippets prepended to every remote command. Workaround files register
  theirs at load time, so deleting the workaround file removes its runtime
  half with it."
  (atom []))

(def ^:private ssh-opts
  ;; port flag differs between ssh (-p) and scp (-P), so it stays out of here
  ["-o" "ConnectTimeout=10" "-o" "StrictHostKeyChecking=accept-new"
   ;; one handshake per run: the first call opens a master, the rest mux over it
   "-o" "ControlMaster=auto" "-o" "ControlPath=/tmp/phone-mux-%r@%h-%p" "-o" "ControlPersist=60"])

(defn ssh
  "Run a command in the device's Termux; returns {:exit :out}."
  [cmd]
  (apply sh-out (concat ["ssh" "-p" "8022"] ssh-opts
                        [(:ssh *dev*)
                         (str/join "; " (concat @ssh-prelude [cmd]))])))

(defn ssh-ok? [cmd] (zero? (:exit (ssh cmd))))

;; adb: connect once per target and remember the outcome — a dozing phone
;; would otherwise re-eat the connect timeout in every step
(def ^:private target-up?
  (memoize
   (fn [t]
     (and (zero? (:exit (sh-out "timeout" "12" "adb" "connect" t)))
          (zero? (:exit (sh-out "timeout" "10" "adb" "-s" t "shell" "true")))))))

(defn adb-plane-up? [] (target-up? (:adb *dev*)))

(defn adb
  "Run adb against the device's tailnet adbd; nil when unreachable or failed."
  [& args]
  (when (adb-plane-up?)
    (let [r (apply sh-out "timeout" "20" "adb" "-s" (:adb *dev*) args)]
      (when (zero? (:exit r)) r))))

;; ------------------------------------------------------------ settings sync

;; every settings-step registers its rows here at load time, so the first
;; check fetches ALL of them in one adb call; apply chains its puts with a
;; full re-get, so the engine's re-check reads genuine read-back values from
;; the cache without another round-trip
(def ^:private settings-rows (atom []))
(def ^:private settings-cache (atom nil))

(defn- get-cmds [rows] (map (fn [[ns k _]] (str "settings get " ns " " k)) rows))

(defn- cache-settings! [out]
  (reset! settings-cache
          (when out (zipmap (map (fn [[ns k _]] [ns k]) @settings-rows)
                            (str/split-lines out)))))

(defn- settings-current? [rows]
  (when (nil? @settings-cache)
    (cache-settings! (:out (adb "shell" (str/join "; " (get-cmds @settings-rows))))))
  (when-let [c @settings-cache]
    (every? (fn [[ns k v]] (= v (get c [ns k]))) rows)))

(defn- put-settings! [rows]
  (let [cmd (str/join "; " (concat (map (fn [[ns k v]] (str "settings put " ns " " k " " v)) rows)
                                   (get-cmds @settings-rows)))]
    (cache-settings! (:out (adb "shell" cmd)))))

(defn settings-step
  "Register a step converging Android `settings` rows [namespace key value]."
  [id doc rows]
  (swap! settings-rows into rows)
  (engine/step! (engine/step id doc :adb
                             #(settings-current? rows)
                             #(put-settings! rows))))

;; ---------------------------------------------------------------- file sync

(defn- src-path [src] (str (force src)))

(defn- local-md5 [path]
  (format "%032x" (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "MD5")
                                          (fs/read-all-bytes path)))))

(defn- stale-rows
  "Rows whose device content differs from the source. One ssh for all rows,
  reading md5s in row order (remote paths expand, so matching by name won't do)."
  [rows]
  (let [cmd (str/join "; " (map (fn [[_ dest _]] (str "md5sum " dest " 2>/dev/null || echo missing"))
                                rows))
        lines (str/split-lines (:out (ssh cmd)))]
    (doall (filter some?
                   (map (fn [row line]
                          (when (not= (local-md5 (src-path (first row)))
                                      (first (str/split line #"\s+")))
                            row))
                        rows (concat lines (repeat "")))))))

(def ^:private termux-prefix "/data/data/com.termux/files/usr")

(defn- push-files
  "Push every row: parent dirs in one ssh, scp per file, modes in one ssh.
  scp's sftp mode expands no remote variables, so $PREFIX dests are translated
  to the literal path for the copy (the ssh-side md5/chmod expand them fine)."
  [rows]
  (ssh (str "mkdir -p " (str/join " " (distinct (map (fn [[_ dest _]] (str (fs/parent dest))) rows)))))
  (doseq [[src dest _] rows]
    (apply p/shell (concat ["scp" "-q" "-P" "8022"] ssh-opts
                           [(src-path src) (str (:ssh *dev*) ":" (str/replace dest "$PREFIX" termux-prefix))])))
  (ssh (str/join "; " (map (fn [[_ dest mode]] (str "chmod " mode " " dest)) rows))))

(defn files-step
  "Register a step converging [src dest mode] payload rows; src may be a delay
  (renders stay unforced until the step runs). The check's stale set is handed
  to apply, so a drifted step costs one digest batch, not two. Optional :after
  shell command runs once following a push (e.g. a reload)."
  [id doc rows & {:keys [after]}]
  (let [stale (atom nil)]
    (engine/step! (engine/step id doc :ssh
                               (fn [] (empty? (reset! stale (stale-rows rows))))
                               (fn []
                                 (push-files @stale)
                                 (when after (ssh after)))))))
