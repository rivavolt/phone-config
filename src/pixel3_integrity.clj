(ns pixel3-integrity
  "Everything that makes Revolut (and other DexProtector / Play-Integrity-gated
  apps) work on the rooted Pixel 3, on top of the one-time APatch + ReZygisk +
  PIF + TrickyStore + Shamiko stack (that stack is a kernel patch and flashed
  modules — see ~/drive/claude-notes/pixel3-revolut-apatch-handoff.md — not
  something a host reconciler installs). What DOES converge from here is the
  userspace config riding on top, in three steps:

    1. local-prop-fix — a tiny module whose post-fs-data.sh resetprop-scrubs the
       userdebug + LineageOS fingerprint props that local RASP checks probe
       (wall 1: local root/ROM detection).
    2. TrickyStore targets — GMS/Vending/checkers in leaf mode, com.revolut.revolut
       in generate mode (`!`), which forges the whole attestation chain from the
       keybox since this device's TEE cannot mint attestation keys.
    3. keybox — a well-formed, unexpired keybox at the TrickyStore path
       (wall 2: Play Integrity). Keyboxes get REVOKED server-side over time,
       which the host cannot see — only Play Integrity or the app reveals it. The
       check only proves well-formed + unexpired; when an app starts failing
       again despite a green check the keybox was revoked, so delete it on-device
       (pixel3-adb shell su -c 'rm /data/adb/tricky_store/keybox.xml') and
       re-apply to pull a fresh one.

  Only the Pixel 3 carries TrickyStore, so on every other phone each check here
  short-circuits to satisfied and nothing runs. The fresh keybox comes from the
  free @IntegrityBox Telegram channel via the workstation's `telegram` CLI,
  whose join/leave verbs (nixos-config 051a2ed3d or later) let it read a channel
  the owner has not joined."
  (:require [engine]
            [transport :refer [adb sh-out repo-file]]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private ts-dir "/data/adb/tricky_store")
(def ^:private keybox-path (str ts-dir "/keybox.xml"))
(def ^:private target-txt (str ts-dir "/target.txt"))
(def ^:private module-dir "/data/adb/modules/local-prop-fix")
(def ^:private channel "@IntegrityBox")

;; leaf mode (the real, broken TEE, merely hooked) for GMS/Vending and the
;; vvb2060 attestation checkers; generate mode (!) only for Revolut, which needs
;; the forged keybox chain. Extra on-device entries are left untouched.
(def ^:private targets
  ["com.android.vending"
   "com.google.android.gms"
   "io.github.vvb2060.keyattestation"
   "io.github.vvb2060.mahoshojo"
   "icu.nullptr.nativetest"
   "com.revolut.revolut!"])

(def ^:private prop-files
  [[(repo-file "payload/pixel3/local-prop-fix/module.prop") (str module-dir "/module.prop") "644"]
   [(repo-file "payload/pixel3/local-prop-fix/post-fs-data.sh") (str module-dir "/post-fs-data.sh") "755"]])

;; --- device root I/O (APatch su; its absence == not the rooted Pixel 3) -----

(defn- su [cmd] (some-> (adb "shell" (str "su -c '" cmd "'")) :out))
(defn- su-ok? [cmd] (some? (adb "shell" (str "su -c '" cmd "'"))))
(defn- trickystore? [] (su-ok? (str "test -d " ts-dir)))

(defn- host-md5 [path] (re-find #"^\S+" (:out (sh-out "md5sum" path))))
(defn- device-md5 [path] (some->> (su (str "md5sum " path " 2>/dev/null")) (re-find #"^\S+")))

(defn- push-root-file!
  "adb push into a shell-writable staging path, then su-cp into the root-owned
  dest (the ssh/scp file plane cannot reach /data/adb)."
  [local dest mode]
  (when (adb "push" local "/data/local/tmp/pixel3-payload")
    (su (str "cp /data/local/tmp/pixel3-payload " dest
             "; chmod " mode " " dest "; chown root:root " dest
             "; rm -f /data/local/tmp/pixel3-payload"))))

;; --- keybox validation (host side) ------------------------------------------

(defn- cert-blocks [xml]
  (re-seq #"(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----" xml))

(defn- notafter-epoch
  "Epoch of a PEM cert's notAfter (openssl + date), or nil. The PEM is
  de-indented first: keybox.xml wraps its certs in XML whitespace openssl
  rejects."
  [pem]
  (let [clean (->> (str/split-lines pem) (map str/trim) (str/join "\n"))
        r (p/shell {:in clean :out :string :err :string :continue true}
                   "openssl" "x509" "-noout" "-enddate")
        end (some-> (:out r) str/trim (str/replace-first "notAfter=" ""))]
    (when (and (zero? (:exit r)) (seq end))
      (let [d (sh-out "date" "-d" end "+%s")]
        (when (zero? (:exit d)) (parse-long (:out d)))))))

(defn- keybox-usable?
  "Well-formed AndroidAttestation whose newest certificate has not expired.
  Revocation is server-side and invisible here."
  [xml]
  (boolean
   (when (and xml (str/includes? xml "<AndroidAttestation>"))
     (let [epochs (keep notafter-epoch (cert-blocks xml))
           now (quot (System/currentTimeMillis) 1000)]
       (and (seq epochs) (> (apply max epochs) now))))))

(defn- newest-keybox-id [json-str]
  (->> (json/parse-string json-str true)
       (filter #(= "keybox.xml" (get-in % [:media :file_name])))
       (map :id)
       (reduce max 0)))

(defn- fetch-keybox!
  "Pull the newest keybox.xml document from the channel with the host `telegram`
  CLI (join -> read -> download -> leave). Returns a local path, or nil."
  []
  (let [tmp (str (fs/create-temp-file {:prefix "pixel3-keybox" :suffix ".xml"}))]
    (try
      (sh-out "telegram" "join" channel)
      (let [id (newest-keybox-id (:out (sh-out "telegram" "read" channel "-n" "80" "--json")))]
        (when (pos? id)
          (sh-out "telegram" "download" channel (str id) "--out" tmp)
          (when (pos? (fs/size tmp)) tmp)))
      (finally (sh-out "telegram" "leave" channel)))))

;; --- steps ------------------------------------------------------------------

(defn- prop-scrub-check []
  (or (not (trickystore?))
      (every? (fn [[local dest _]] (= (host-md5 local) (device-md5 dest))) prop-files)))

(defn- prop-scrub-apply! []
  (su (str "mkdir -p " module-dir))
  (doseq [[local dest mode] prop-files] (push-root-file! local dest mode)))

(defn- target-check []
  (or (not (trickystore?))
      (let [have (set (str/split-lines (or (su (str "cat " target-txt)) "")))]
        (every? have targets))))

(defn- target-apply! []
  (let [have (set (str/split-lines (or (su (str "cat " target-txt)) "")))
        missing (remove have targets)]
    (when (seq missing)
      (su (str (str/join "; " (map #(str "echo " % " >> " target-txt) missing))
               "; killall keystore2")))))

(defn- keybox-check [] (or (not (trickystore?)) (keybox-usable? (su (str "cat " keybox-path)))))

(defn- keybox-apply! []
  (when-let [local (fetch-keybox!)]
    (when (keybox-usable? (slurp local))
      (when (adb "push" local "/data/local/tmp/pixel3-keybox.xml")
        (su (str "cp -a " keybox-path " " keybox-path ".bak 2>/dev/null; "
                 "cp /data/local/tmp/pixel3-keybox.xml " keybox-path "; "
                 "chmod 644 " keybox-path "; chown root:root " keybox-path "; "
                 "rm -f /data/local/tmp/pixel3-keybox.xml; killall keystore2"))))))

(engine/step! (engine/step :pixel3-prop-scrub
                           "local-prop-fix module (scrub userdebug + LineageOS props)"
                           :adb prop-scrub-check prop-scrub-apply!))

(engine/step! (engine/step :pixel3-tricky-targets
                           "TrickyStore targets (Revolut in generate mode)"
                           :adb target-check target-apply!))

(engine/step! (engine/step :pixel3-keybox
                           "valid (unexpired) TrickyStore keybox installed"
                           :adb keybox-check keybox-apply!))
