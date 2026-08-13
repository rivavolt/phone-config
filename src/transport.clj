(ns transport
  "All I/O to a device: ssh into Termux (port 8022, connection-multiplexed),
  adb against the tailnet adbd (connected once per run), and content-addressed
  file sync. The target device is bound once per run via *dev*
  {:ssh user@host :adb host:port}. File sources are absolute paths — use
  repo-file for files vendored in this repo; rendered files are already
  absolute."
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
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

;; adb: connect once per run and remember the outcome — a dozing phone would
;; otherwise re-eat the connect timeout in every step
(def ^:private adb-up (atom {}))

(defn adb-plane-up? []
  (let [t (:adb *dev*)]
    (if (contains? @adb-up t)
      (get @adb-up t)
      (let [up (and (zero? (:exit (sh-out "timeout" "12" "adb" "connect" t)))
                    (zero? (:exit (sh-out "timeout" "10" "adb" "-s" t "shell" "true"))))]
        (swap! adb-up assoc t up)
        up))))

(defn adb
  "Run adb against the device's tailnet adbd; nil when unreachable or failed."
  [& args]
  (when (adb-plane-up?)
    (let [r (apply sh-out "timeout" "20" "adb" "-s" (:adb *dev*) args)]
      (when (zero? (:exit r)) r))))

;; ---------------------------------------------------------------- file sync

(defn- local-md5 [path]
  (first (str/split (:out (sh-out "md5sum" path)) #"\s+")))

(defn- stale-rows
  "Rows whose device content differs from the source. One ssh for all rows,
  reading md5s in row order (remote paths expand, so matching by name won't do)."
  [rows]
  (let [cmd (str/join "; " (map (fn [[_ dest _]] (str "md5sum " dest " 2>/dev/null || echo missing"))
                                rows))
        lines (str/split-lines (:out (ssh cmd)))]
    (doall (keep (fn [[row line]]
                   (when (not= (local-md5 (first row))
                               (first (str/split (or line "") #"\s+")))
                     row))
                 (map vector rows (concat lines (repeat nil)))))))

(defn- push-files
  "Push every row: parent dirs in one ssh, scp per file, modes in one ssh."
  [rows]
  (ssh (str "mkdir -p " (str/join " " (distinct (map (fn [[_ dest _]] (str (fs/parent dest))) rows)))))
  (doseq [[src dest _] rows]
    (apply p/shell (concat ["scp" "-q" "-P" "8022"] ssh-opts [src (str (:ssh *dev*) ":" dest)])))
  (ssh (str/join "; " (map (fn [[_ dest mode]] (str "chmod " mode " " dest)) rows))))

(defn settings-step
  "Step map converging Android `settings` rows [namespace key value], batched
  into one adb call each way."
  [id doc rows]
  {:id id :doc doc :plane :adb
   :check (fn [] (= (map peek rows)
                    (some-> (adb "shell" (clojure.string/join "; " (map (fn [[ns k _]] (str "settings get " ns " " k)) rows)))
                            :out clojure.string/split-lines)))
   :apply (fn [] (adb "shell" (clojure.string/join "; " (map (fn [[ns k v]] (str "settings put " ns " " k " " v)) rows))))})

(defn files-step
  "Step map converging [src dest mode] payload rows (src absolute; rows-fn so
  renders are only forced when the step runs). Optional :after shell command
  runs once following a push (e.g. a reload)."
  [id doc rows-fn & {:keys [after]}]
  {:id id :doc doc :plane :ssh
   :check (fn [] (empty? (stale-rows (rows-fn))))
   :apply (fn []
            (push-files (stale-rows (rows-fn)))
            (when after (ssh after)))})
