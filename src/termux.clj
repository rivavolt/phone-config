(ns termux
  "Termux-plane desired state, applied over ssh: packages, login shell, sshd,
  payload files, supervised services, uv tools, and the binary-fetched tools
  no Termux repo serves."
  (:require [engine :refer [defstep log]]
            [transport :refer [repo ssh ssh-ok? file-current? push-file]]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- wanted-packages []
  (->> (fs/read-all-lines (str repo "/packages.txt"))
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       set))

(defn- installed-packages []
  (set (str/split-lines (:out (ssh "pkg list-installed 2>/dev/null | tail -n +2 | cut -d/ -f1")))))

(defn- available-packages []
  (set (str/split-lines (:out (ssh "apt-cache pkgnames")))))

(defstep :packages "packages.txt installed"
  ;; names the repos no longer serve can never converge — not drift
  :check (empty? (filter (available-packages)
                         (remove (installed-packages) (wanted-packages))))
  :apply! (let [available (available-packages)
                missing   (remove (installed-packages) (wanted-packages))
                gone      (remove available missing)
                install   (filter available missing)]
            ;; one unknown name aborts a whole apt batch, so install only the
            ;; servable intersection and report the rest
            (when (seq gone)
              (log :warn :packages (str "not in any repo: " (str/join " " gone))))
            (when (seq install)
              (ssh (str "DEBIAN_FRONTEND=noninteractive apt-get install -y"
                        " -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef "
                        (str/join " " install))))))

(defstep :login-shell "login shell is zsh"
  :check (= "zsh" (:out (ssh "basename $(readlink ~/.termux/shell 2>/dev/null) 2>/dev/null")))
  :apply! (ssh "chsh -s zsh"))

(defstep :ssh-host-keys "sshd host keys present"
  :check (ssh-ok? "ls $PREFIX/etc/ssh/ssh_host_*_key >/dev/null 2>&1")
  :apply! (ssh "ssh-keygen -A"))

(def payload
  [["authorized_keys"           "~/.ssh/authorized_keys"                    "600"]
   ["ssh_config"                "~/.ssh/config"                             "600"]
   ["sshd_config.d/listen.conf" "$PREFIX/etc/ssh/sshd_config.d/listen.conf" "644"]
   ["termux-adb-bootstrap"      "$PREFIX/bin/termux-adb-bootstrap"          "755"]
   [".termux/boot/start-sshd"   "~/.termux/boot/start-sshd"                 "755"]])

(defstep :files "payload files current"
  :check (do (ssh "mkdir -p ~/.ssh ~/.termux/boot $PREFIX/etc/ssh/sshd_config.d")
             (every? (fn [[src dest _]] (file-current? src dest)) payload))
  :apply! (doseq [[src dest mode] payload
                  :when (not (file-current? src dest))]
            (log :push :files (str src " -> " dest))
            (push-file src dest mode)))

(defstep :adb-boot-hook "adbd-on-5555 re-established at boot"
  ;; only meaningful once the fleet adb client key is on the device (onboard
  ;; seeds it); wireless-debug authenticates with that key, so no pairing
  :check (or (not (ssh-ok? "test -f ~/.android/adbkey"))
             (ssh-ok? "test -x ~/.termux/boot/10-adb-bootstrap"))
  :apply! (ssh (str "printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/sh' "
                    "'termux-wake-lock 2>/dev/null || true' 'termux-adb-bootstrap' "
                    "'termux-wake-unlock 2>/dev/null || true' > ~/.termux/boot/10-adb-bootstrap; "
                    "chmod 755 ~/.termux/boot/10-adb-bootstrap")))

(defstep :services "sshd supervised by termux-services"
  :check (ssh-ok? "SVDIR=$PREFIX/var/service sv status sshd 2>/dev/null | grep -q '^run:'")
  :apply! (ssh (str "rm -f $PREFIX/var/service/sshd/down; "
                    "pgrep -x runsvdir >/dev/null || setsid sh -c 'SVDIR=$PREFIX/var/service exec runsvdir $PREFIX/var/service' >/dev/null 2>&1 & "
                    "sleep 2; SVDIR=$PREFIX/var/service sv up sshd")))

(defstep :uv-tools "uv tools from ~/.config/uv-tools.txt"
  :check (or (not (ssh-ok? "test -f ~/.config/uv-tools.txt"))
             (ssh-ok? (str "ok=1; while read -r line; do t=${line%% *}; "
                           "test -x ~/.local/bin/$t || ok=0; done < ~/.config/uv-tools.txt; test $ok = 1")))
  :apply! (ssh (str "while read -r line; do uv tool install $line >/dev/null 2>&1 "
                    "|| echo \"uv tool failed: $line\" >&2; done < ~/.config/uv-tools.txt")))

;; Binary-fetched tools missing from every Termux repo. GitHub's API is read
;; with a real JSON parser on the host — the on-phone gojq this replaces was
;; the first casualty of the Android 16 Go-binary breakage.

(defstep :doctl "doctl release binary"
  :check (ssh-ok? "command -v doctl >/dev/null")
  :apply! (let [tag (-> (http/get "https://api.github.com/repos/digitalocean/doctl/releases/latest")
                        :body (json/parse-string true) :tag_name)
                url (format "https://github.com/digitalocean/doctl/releases/download/%s/doctl-%s-linux-arm64.tar.gz"
                            tag (subs tag 1))]
            (ssh (format "cd $TMPDIR && curl -fsSL '%s' -o d.tgz && tar -xzf d.tgz doctl && install -m755 doctl $PREFIX/bin/doctl && rm -f d.tgz doctl" url))))

(defstep :gcloud "google-cloud-sdk under ~/google-cloud-sdk"
  :check (ssh-ok? "test -x ~/google-cloud-sdk/bin/gcloud")
  :apply! (ssh (str "cd $TMPDIR && curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-arm.tar.gz -o g.tgz "
                    "&& tar -xzf g.tgz -C ~ && rm g.tgz; "
                    "for b in gcloud gsutil bq; do ln -sf ~/google-cloud-sdk/bin/$b $PREFIX/bin/$b; done")))

(defstep :pnpm "pnpm via npm"
  :check (ssh-ok? "command -v pnpm >/dev/null")
  :apply! (ssh "npm install -g pnpm"))

(defstep :boot-apk "Termux:Boot APK installed (hooks never fire without it)"
  :check (ssh-ok? "pm path com.termux.boot >/dev/null 2>&1")
  :apply! (throw (ex-info "install over adb: phone onboard <serial>" {})))
