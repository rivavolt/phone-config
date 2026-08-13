(ns airplane-radios
  "Airplane mode kills only the cell radio: WiFi and Bluetooth stay up, so the
  tailnet (and with it adb/ssh) survives airplane mode, and earbuds keep
  playing. Both remain manually toggleable inside airplane mode."
  (:require [engine :refer [step!]]
            [transport :refer [settings-step]]))

(step! (settings-step :airplane-radios "airplane mode spares wifi + bluetooth"
                      [["global" "airplane_mode_radios" "cell,nfc,wimax"]
                       ["global" "airplane_mode_toggleable_radios" "bluetooth,wifi"]]))
