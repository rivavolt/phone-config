(ns termux-api
  "`termux-location`, `termux-tts-speak` and the rest of the termux-api CLI are
  only half the feature: the scripts in $PREFIX/bin broadcast to the companion
  **Termux:API app** and block on its reply, so with the app missing every one
  of them hangs forever rather than failing. packages.txt installs the CLI half;
  this file owns the app half.

  The APK cannot simply be downloaded from F-Droid: the Termux apps share a
  sharedUserId, so Termux:API must carry the SAME signing key as the installed
  Termux or the install dies with INSTALL_FAILED_SHARED_USER_INCOMPATIBLE. A
  sideloaded Termux is the github.debug build, so its Termux:API is the
  `termux-api-app_*+github.debug.apk` asset from termux/termux-api releases —
  hence a manual step with the remediation in its doc rather than a guessed
  auto-install.

  What does converge here: the runtime permissions the CLI depends on (a
  freshly-installed app holds none of them, so termux-location returns an error
  until granted) and the doze exemption that keeps the app answering when the
  screen is off."
  (:require [engine :refer [defstep]]
            [transport :refer [adb require-pkgs!]]
            [clojure.string :as str]))

;; the package this policy is built on
(require-pkgs! "termux-api")

(def ^:private pkg "com.termux.api")

;; Only runtime (dangerous) permissions appear here — install-time ones are
;; granted by the manifest. Each maps to a CLI verb: location -> termux-location,
;; camera -> termux-camera-photo, record audio -> termux-microphone-record,
;; contacts/sms/phone -> termux-contact-list / termux-sms-* / termux-telephony-*.
(def ^:private permissions
  ["android.permission.ACCESS_FINE_LOCATION"
   "android.permission.ACCESS_COARSE_LOCATION"
   "android.permission.ACCESS_BACKGROUND_LOCATION"
   "android.permission.CAMERA"
   "android.permission.RECORD_AUDIO"
   "android.permission.READ_CONTACTS"
   "android.permission.READ_PHONE_STATE"
   "android.permission.READ_SMS"
   "android.permission.BODY_SENSORS"])

(defn- installed? [] (some? (adb "shell" (str "pm path " pkg))))

(defn- granted
  "Runtime permissions currently granted to the app, as a set."
  []
  (when-let [r (adb "shell" (str "dumpsys package " pkg))]
    (->> (str/split-lines (:out r))
         (keep #(second (re-find #"(android\.permission\.\w+): granted=true" %)))
         set)))

(defstep :termux-api-apk
  "Termux:API APK installed — install the github.debug asset matching Termux's signature"
  :plane :adb
  :check (installed?))

(defstep :termux-api-permissions "Termux:API holds the runtime permissions its CLI needs"
  :plane :adb
  :when (installed?)
  :check (when-let [g (granted)] (every? g permissions))
  :apply! (doseq [p permissions]
            (adb "shell" (str "pm grant " pkg " " p))))

(defstep :termux-api-doze "Termux:API doze-exempt (answers with the screen off)"
  :plane :adb
  :when (installed?)
  :check (when-let [r (adb "shell" "dumpsys" "deviceidle" "whitelist")]
           (str/includes? (:out r) pkg))
  :apply! (adb "shell" "dumpsys" "deviceidle" "whitelist" (str "+" pkg)))
