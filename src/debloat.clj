(ns debloat
  "Remove the LineageOS/AOSP built-in apps made redundant by the installed Google
  apps suite (Phone, Contacts, Messages, Clock, Calculator, Photos, Chrome,
  Calendar, Camera), plus the LineageOS extras the owner doesn't use. Each
  removal that has a Google counterpart is gated on that counterpart being
  installed, so this can never strand a phone with no dialer/browser/etc.;
  the extras (nil replacement) are removed wherever present. Uninstall is
  per-user (`pm uninstall --user 0`) — the APK stays on the system partition and
  a removal is reversible with `pm install-existing <pkg>` or a factory reset."
  (:require [engine]
            [transport :refer [adb]]
            [clojure.string :as str]))

;; [redundant-pkg  replacement-pkg-or-nil]. A non-nil replacement guards the
;; removal (only strip the duplicate when the Google app that supersedes it is
;; actually installed); nil marks a standalone extra with no equivalent.
(def ^:private removals
  [["com.android.dialer"        "com.google.android.dialer"]
   ["com.android.contacts"      "com.google.android.contacts"]
   ["com.android.messaging"     "com.google.android.apps.messaging"]
   ["com.android.deskclock"     "com.google.android.deskclock"]
   ["com.android.calculator2"   "com.google.android.calculator"]
   ["com.android.gallery3d"     "com.google.android.apps.photos"]
   ["org.lineageos.jelly"       "com.android.chrome"]
   ["org.lineageos.etar"        "com.google.android.calendar"]
   ["org.lineageos.glimpse"     "com.google.android.apps.photos"]
   ["org.lineageos.aperture"    "com.google.android.GoogleCamera"]
   ["org.lineageos.twelve"      nil]
   ["org.lineageos.recorder"    nil]
   ["org.lineageos.audiofx"     nil]
   ["org.lineageos.camelot"     nil]
   ["org.lineageos.backgrounds" nil]])

(defn- installed-set
  "Packages installed for user 0 (a per-user uninstall drops the package from
  this list even though its APK survives), or nil when the adb plane is down."
  []
  (some->> (adb "shell" "pm list packages --user 0")
           :out
           str/split-lines
           (map #(str/replace % "package:" ""))
           set))

(defn- actionable
  "Removals still present whose guard (replacement) is satisfied."
  [installed]
  (filter (fn [[pkg repl]] (and (installed pkg) (or (nil? repl) (installed repl))))
          removals))

(defn- debloat-check []
  (let [inst (installed-set)]
    (or (nil? inst) (empty? (actionable inst)))))

(defn- debloat-apply! []
  (when-let [inst (installed-set)]
    (doseq [[pkg _] (actionable inst)]
      (adb "shell" (str "pm uninstall --user 0 " pkg)))))

(engine/step! (engine/step :debloat
                           "remove built-in apps duplicated by the installed Google apps"
                           :adb debloat-check debloat-apply!))
