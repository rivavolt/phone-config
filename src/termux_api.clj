(ns termux-api
  "`termux-location`, `termux-tts-speak` and the rest of the termux-api CLI are
  only half the feature: the scripts in $PREFIX/bin broadcast to the companion
  **Termux:API app** and block on its reply, so with the app missing every one
  of them hangs forever rather than failing. packages.txt installs the CLI half;
  this file owns the app half.

  Which APK to fetch is not a preference: the Termux apps share a sharedUserId,
  so the plugin must carry the SAME signing key as the installed Termux or the
  install dies with INSTALL_FAILED_SHARED_USER_INCOMPATIBLE. The source is
  therefore read off the main app's installer — an F-Droid client means the
  F-Droid build, anything else (sideloaded, or Obtainium tracking the releases)
  means the `+github.debug` asset.

  Keeping it current is deliberately NOT done here: a phone that installed
  Termux through Droid-ify or Obtainium already has an updater watching that
  source, and a second one racing it would just fight over versions. This step
  owns presence; the on-device updater owns the version.

  Also converged: the runtime permissions the CLI depends on (a freshly
  installed app holds none, so termux-location errors until granted) and the
  doze exemption that keeps the app answering with the screen off."
  (:require [engine :refer [defstep]]
            [transport :refer [adb sh-out require-pkgs!] :as transport]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
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

(defn- termux-source
  "Signing source of the MAIN Termux app, which the plugin has to match. An
  F-Droid client as installer means the F-Droid build; a null installer
  (sideloaded) or Obtainium means the github.debug asset."
  []
  (when-let [r (adb "shell" (str "pm list packages -i com.termux"))]
    (if (re-find #"installer=(com\.looker\.droidify|org\.fdroid)" (:out r))
      :fdroid
      :github)))

(defn- apk-url
  "Download URL for the build matching `src`."
  [src]
  (case src
    :fdroid (let [j (json/parse-string (:body (http/get "https://f-droid.org/api/v1/packages/com.termux.api")) true)]
              (str "https://f-droid.org/repo/com.termux.api_" (:suggestedVersionCode j) ".apk"))
    :github (let [j (json/parse-string (:body (http/get "https://api.github.com/repos/termux/termux-api/releases/latest")) true)]
              (->> (:assets j)
                   (filter #(str/ends-with? (:name %) "+github.debug.apk"))
                   first
                   :browser_download_url))))

(defn- install-apk! []
  (when-let [src (termux-source)]
    (when-let [url (apk-url src)]
      (let [tmp (str (fs/create-temp-file {:prefix "termux-api" :suffix ".apk"}))]
        (io/copy (:body (http/get url {:as :stream})) (io/file tmp))
        (sh-out "adb" "-s" (:adb transport/*dev*) "install" "-r" tmp)
        (fs/delete-if-exists tmp)))))

(defstep :termux-api-apk "Termux:API app installed (build matching Termux's signature)"
  :plane :adb
  :check (installed?)
  :apply! (install-apk!))

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
