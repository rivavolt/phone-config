(ns android16-exec
  "Workaround, not desired state — delete this file when its sunset arrives."
  (:require [engine :refer [defstep]]
            [transport :refer [ssh ssh-ok? ssh-prelude]]))

;; Android 16 (SDK 36): termux-exec routes every exec through /system/bin/
;; linker64. C binaries get their argv fixed up by the preload constructor, but
;; Go reads argv off the raw stack first, so every Go binary (gojq, doctl, gh…)
;; sees its own path as argv[1] and breaks. The app targets SDK 28, whose
;; SELinux domain still permits direct exec of app-data binaries, so forcing
;; linker-exec off is safe — and a no-op on older Android.
;; DELETE WHEN: Termux app ≥0.119 + termux-exec handle system-linker-exec for
;; Go programs (upstream tracking: termux/termux-exec#24).

(def ^:private mode-line "export TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=disable")

;; the runtime half: non-login remote commands never read profile.d, so every
;; ssh command sources the mode file — registered here so deleting this file
;; removes it too
(swap! ssh-prelude conj ". $PREFIX/etc/profile.d/termux-exec-mode.sh 2>/dev/null")

(defstep :exec-mode "system-linker-exec disabled (Android 16 Go-binary fix)"
  :check (ssh-ok? (str "test -f $PREFIX/etc/profile.d/termux-exec-mode.sh"
                       " && grep -q TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE $PREFIX/etc/zshenv"))
  :apply! (ssh (format (str "mkdir -p $PREFIX/etc/profile.d; "
                            "echo '%s' > $PREFIX/etc/profile.d/termux-exec-mode.sh; "
                            "grep -q TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE $PREFIX/etc/zshenv 2>/dev/null || "
                            "echo '%s' >> $PREFIX/etc/zshenv")
                       mode-line mode-line)))
