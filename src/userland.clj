(ns userland
  "The dev environment expected in Termux: packages, zsh as login shell, the
  outbound ssh client config, Termux UI config (font, properties), and the
  binary-fetched tools no Termux repo serves."
  (:require [engine :refer [defstep log]]
            [engine]
            [transport :refer [repo-file ssh ssh-ok? files-step]]
            [nixos-config]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn wanted-packages []
  (->> (fs/read-all-lines (repo-file "packages.txt"))
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       set))

(defn- pkg-state
  "Installed + repo-servable package names, one ssh."
  []
  (let [[installed _ available]
        (partition-by #(= "===" %)
                      (str/split-lines
                       (:out (ssh "pkg list-installed 2>/dev/null | tail -n +2 | cut -d/ -f1; echo ===; apt-cache pkgnames"))))]
    {:installed (set installed) :available (set available)}))

(defstep :packages "packages.txt installed"
  ;; names the repos no longer serve can never converge — not drift
  :check (let [{:keys [installed available]} (pkg-state)]
           (empty? (filter available (remove installed (wanted-packages)))))
  :apply! (let [{:keys [installed available]} (pkg-state)
                missing (remove installed (wanted-packages))
                gone    (remove available missing)
                install (filter available missing)]
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

(files-step :ssh-client-config "outbound ssh config current"
            [[nixos-config/ssh-config-file "~/.ssh/config" "600"]])

(files-step :termux-ui "font, properties current"
            [[(repo-file ".termux/font.ttf")          "~/.termux/font.ttf"          "644"]
             [(repo-file ".termux/termux.properties") "~/.termux/termux.properties" "644"]]
            :after "termux-reload-settings 2>/dev/null || true")

;; Binary-fetched tools missing from every Termux repo — one pattern: fetch a
;; tarball, run its install command. GitHub's API is read with a real JSON
;; parser on the host; the on-phone gojq this replaces was the first casualty
;; of the Android 16 Go-binary breakage.
(def fetched-tools
  [{:id :doctl :doc "doctl release binary"
    :check "command -v doctl >/dev/null"
    :url #(let [tag (-> (http/get "https://api.github.com/repos/digitalocean/doctl/releases/latest")
                        :body (json/parse-string true) :tag_name)]
            (format "https://github.com/digitalocean/doctl/releases/download/%s/doctl-%s-linux-arm64.tar.gz"
                    tag (subs tag 1)))
    :install "tar -xzf t.tgz doctl && install -m755 doctl $PREFIX/bin/doctl && rm -f doctl"}
   {:id :gcloud :doc "google-cloud-sdk under ~/google-cloud-sdk"
    :check "test -x ~/google-cloud-sdk/bin/gcloud"
    :url (constantly "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-arm.tar.gz")
    :install "tar -xzf t.tgz -C ~ && for b in gcloud gsutil bq; do ln -sf ~/google-cloud-sdk/bin/$b $PREFIX/bin/$b; done"}])

(doseq [{:keys [id doc check url install]} fetched-tools]
  (engine/step! (engine/step id doc :ssh
                             #(ssh-ok? check)
                             #(ssh (format "cd $TMPDIR && curl -fsSL '%s' -o t.tgz && %s && rm -f t.tgz"
                                           (url) install)))))

(defstep :pnpm "pnpm via npm"
  :check (ssh-ok? "command -v pnpm >/dev/null")
  :apply! (ssh "npm install -g pnpm"))

(defn adopt
  "Add packages manually installed on the device to packages.txt. Stateless:
  apply never uninstalls, so this is the only adoptable drift direction;
  removals are an edit to packages.txt."
  []
  (let [manual (set (str/split-lines (:out (ssh "apt-mark showmanual 2>/dev/null"))))
        new (sort (remove (wanted-packages) manual))]
    (if (empty? new)
      (println "packages.txt already covers everything manually installed")
      (do (spit (repo-file "packages.txt")
                (str (str/join "\n" (sort (into (wanted-packages) new))) "\n"))
          (println "adopted:" (str/join " " new))))))
