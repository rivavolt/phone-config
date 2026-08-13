(ns fast-animations
  "Window/transition/animator scales at half duration: the UI keeps its motion
  cues but stops making you wait for them."
  (:require [transport :refer [settings-step]]))

(settings-step :fast-animations "animation scales 0.5x"
               [["global" "window_animation_scale" "0.5"]
                ["global" "transition_animation_scale" "0.5"]
                ["global" "animator_duration_scale" "0.5"]])
