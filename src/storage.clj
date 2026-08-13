(ns storage
  "Termux reads shared storage: the all-files appop (grantable headlessly over
  adb; takes effect when the app's processes next start, reboot included) and
  the ~/storage symlink farm that termux-setup-storage would build through the
  app UI."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? adb]]
            [clojure.string :as str]))

(defstep :storage-access "Termux holds MANAGE_EXTERNAL_STORAGE"
  :plane :adb
  :check (some-> (adb "shell" "appops" "get" "com.termux" "MANAGE_EXTERNAL_STORAGE")
                 :out (str/includes? "allow"))
  :apply! (adb "shell" "appops" "set" "com.termux" "MANAGE_EXTERNAL_STORAGE" "allow"))

(defstep :storage-links "~/storage symlink farm present"
  :check (ssh-ok? "test -L ~/storage/shared")
  :apply! (ssh (str "mkdir -p ~/storage; ln -sf /storage/emulated/0 ~/storage/shared; "
                    "for d in DCIM Download Pictures Music Movies Documents; do "
                    "ln -sf /storage/emulated/0/$d ~/storage/$(echo $d | tr A-Z a-z); done")))
