(ns engine
  "Converge mechanism: an ordered registry of steps, each a check plus an
  apply. A step whose check passes is skipped; otherwise apply runs and the
  re-check decides fixed vs failed. Knows nothing about phones.")

(def steps (atom []))
(def failures (atom 0))

(defmacro defstep
  "Declare a converge step. `check` truthy means already satisfied; `apply!`
  mutates. Registration order is execution order."
  [id doc & {:keys [check apply!]}]
  `(swap! steps conj {:id ~id :doc ~doc
                      :check (fn [] ~check)
                      :apply (fn [] ~apply!)}))

(defn log [tag id msg]
  (println (format "  %-5s %-16s %s" (name tag) (name id) msg)))

(defn converge [{:keys [check-only]}]
  (doseq [{:keys [id doc check apply]} @steps]
    (try
      (cond
        (check)     (log :ok id doc)
        check-only  (do (log :DRIFT id doc) (swap! failures inc))
        :else       (do (apply)
                        (if (check)
                          (log :fixed id doc)
                          (do (log :FAIL id doc) (swap! failures inc)))))
      (catch Exception e
        (log :ERROR id (str doc " — " (ex-message e)))
        (swap! failures inc)))))
