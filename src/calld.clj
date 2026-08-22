(ns calld
  "The tapped handset carries the call daemon: calld (rivavolt/call), the static
  aarch64-musl binary that places and answers calls, taps both audio legs and
  injects speech into the uplink. The binary is BUILT from the call flake at
  apply time, never vendored — same reasoning as the nixos-config renders: a
  committed binary is a cache with no invalidation. It lands over adb in
  /data/local/tmp, which is DE storage (reachable pre-unlock, so a boot
  supervisor can exec it in BFU) and shell-writable (no su hop, unlike the
  /data/adb payloads).

  Only the binary converges from here, and it lands COLD — this step never
  starts, restarts or signals the daemon. Restarting calld out from under a
  live call drops the tap mid-conversation and can leave the ADSP mixer routing
  latched for the next call, so a converge must not touch a running instance;
  the next start picks up the new binary. Launching and keeping it alive is a
  separate concern (an on-device supervisor), and its contract is strict: exec
  `/data/local/tmp/calld serve --port 8790` AS ROOT and let it self-drop to uid
  2000 keeping gid 1005(audio)+3003(inet) — do not pin a uid or strip groups,
  or /dev/snd or the AF_INET listener become unreachable — and stop it with
  SIGTERM, never SIGKILL, so it can revert the mixer on the way out."
  (:require [engine]
            [transport :refer [adb sh-out]]
            [clojure.string :as str]))

;; built from the pushed flake by default, not the shared ~/dev/call checkout,
;; which may be mid-edit; CALL_FLAKE overrides for local iteration
(def ^:private flake (or (System/getenv "CALL_FLAKE") "github:rivavolt/call"))
(def ^:private dest "/data/local/tmp/calld")

(def ^:private binary
  (delay
    (let [out (str/trim (:out (sh-out "nix" "build" "--no-link" "--print-out-paths"
                                      (str flake "#calld"))))]
      (when (str/blank? out) (throw (ex-info "nix build produced no calld" {:flake flake})))
      (str out "/bin/calld"))))

(defn- host-md5 [path] (re-find #"^\S+" (:out (sh-out "md5sum" path))))
(defn- device-md5 [path] (some->> (adb "shell" (str "md5sum " path " 2>/dev/null")) :out (re-find #"^\S+")))

;; only the Pixel 3 is the tapped handset — the audio path is SDM845-specific
;; mixer routing, and being the fleet's call tap is a designation, not a cheap
;; probe, so the guard is the device identity
(defn- call-tap? [] (= (:ssh transport/*dev*) "pixel3"))

(engine/step! (engine/step
               :calld-binary
               "calld current in /data/local/tmp (cold; a supervisor launches it)"
               :adb
               (fn [] (= (host-md5 @binary) (device-md5 dest)))
               (fn []
                 (adb "push" @binary dest)
                 (adb "shell" (str "chmod 755 " dest)))
               call-tap?))
