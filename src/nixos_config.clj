(ns nixos-config
  "The nixos-config seam: authorized_keys and ssh_config are RENDERED from
  ~/dev/nixos-config at apply time, never vendored — a committed render is a
  cache with no invalidation, and its sync script rots silently (the old
  sync-keys shipped a stale key set for months). Renders are memoized per run."
  (:require [transport :refer [sh-out]]
            [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def nixos-config
  (or (System/getenv "NIXOS_CONFIG") (str (fs/expand-home "~/dev/nixos-config"))))

(def authorized-keys-file
  "Path to freshly rendered authorized_keys: every machine userKey except the
  phones' own (androidDevice = true — a phone shouldn't list itself)."
  (delay
    (let [expr (format
                "let m = import %s/flake/machines.nix; ks = (import %s/modules/shared/ssh-keys.nix).userKeys; in builtins.attrValues (removeAttrs ks (builtins.filter (n: m.${n}.androidDevice or false) (builtins.attrNames m)))"
                nixos-config nixos-config)
          keys (-> (sh-out "nix" "eval" "--impure" "--json" "--expr" expr)
                   :out json/parse-string)
          f (str (fs/create-temp-file))]
      (when (empty? keys) (throw (ex-info "no keys rendered" {})))
      (spit f (str (str/join "\n" (sort keys)) "\n"))
      f)))

(def ssh-config-file
  "Path to the phone ~/.ssh/config, rendered by nixos-config's
  phone-ssh-config generator (the config layout lives there)."
  (delay
    (let [out (str/trim (:out (sh-out "nix" "build" "--no-link" "--print-out-paths" "--impure"
                                      "--expr" (format "(import <nixpkgs> { overlays = [ (import %s/pkgs) ]; }).andrei.phone-ssh-config.config" nixos-config))))]
      (when (str/blank? out) (throw (ex-info "nix build produced no path" {})))
      out)))
