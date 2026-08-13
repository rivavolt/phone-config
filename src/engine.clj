(ns engine
  "Converge mechanism: an ordered registry of steps, each a check plus an
  apply. A step whose check passes is skipped; otherwise apply runs and the
  re-check decides fixed vs failed. A step without an apply is a manual
  precondition: its drift is reported with the doc as the remediation hint.
  Steps declare the transport plane they need; converge probes each plane once
  and reports steps on a dead plane as skipped — reachability is not drift.
  Knows nothing about phones.")

(def steps (atom []))

(defn step! [m] (swap! steps conj m))

(defmacro defstep
  "Declare a converge step. `check` truthy means already satisfied; `apply!`
  mutates (omit it for manual-only steps). Registration order is execution
  order."
  [id doc & {:keys [plane check apply!]}]
  `(step! {:id ~id :doc ~doc :plane ~(or plane :ssh)
           :check (fn [] ~check)
           :apply ~(when apply! `(fn [] ~apply!))}))

(defn log [tag id msg]
  (println (format "  %-6s %-18s %s" (name tag) (name id) msg)))

(defn converge
  "Run all steps; returns the failure count. probes maps plane -> (fn)->bool,
  consulted once per plane."
  [{:keys [check-only probes]}]
  (let [plane-up? (memoize (fn [plane] (if-let [f (get probes plane)] (f) true)))
        fails (atom 0)]
    (doseq [{:keys [id doc plane check apply]} @steps]
      (try
        (cond
          (not (plane-up? plane))
          (log :skip id (str doc " — " (name plane) " unreachable"))

          (check)
          (log :ok id doc)

          (nil? apply)
          (do (log :MANUAL id doc) (swap! fails inc))

          check-only
          (do (log :DRIFT id doc) (swap! fails inc))

          :else
          (do (apply)
              (if (check)
                (log :fixed id doc)
                (do (log :FAIL id doc) (swap! fails inc)))))
        (catch Exception e
          (log :ERROR id (str doc " — " (ex-message e)))
          (swap! fails inc))))
    @fails))
