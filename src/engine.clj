(ns engine
  "Converge mechanism: an ordered registry of steps, each a check plus an
  apply. A step whose check passes is skipped; otherwise apply runs and the
  re-check decides fixed vs failed. A step without an apply is a manual
  precondition: its drift is reported with the doc as the remediation hint.
  Knows nothing about phones.

  Two conditionals decide whether a step runs at all, and neither counts as
  drift. A step declares the transport `plane` it needs, and converge probes
  each plane once — a step on a dead plane is `skip`, because reachability is
  not drift. A step may also declare `applies?`: whether it is meaningful on
  THIS device at all — TrickyStore steps on a phone with no TrickyStore, or
  duplicate-app removal on a ROM that ships none. That is reported as `na`.
  Without it the question has nowhere to live but `check`, which then answers
  'satisfied' for a step that simply does not apply, and a reader cannot tell a
  converged device from an irrelevant one.")

(def steps (atom []))

(defn step
  "The one home of the step shape. apply may be nil (manual-only step);
  applies? may be nil (the step is universal)."
  ([id doc plane check apply] (step id doc plane check apply nil))
  ([id doc plane check apply applies?]
   {:id id :doc doc :plane plane :check check :apply apply :applies? applies?}))

(defn step! [m] (swap! steps conj m))

(defmacro defstep
  "Declare a converge step. `check` truthy means already satisfied; `apply!`
  mutates (omit it for manual-only steps); `when` guards applicability (omit it
  when the step is universal). Registration order is execution order."
  [id doc & {:keys [plane check apply! when]}]
  `(step! (step ~id ~doc ~(or plane :ssh)
                (fn [] ~check)
                ~(clojure.core/when apply! `(fn [] ~apply!))
                ~(clojure.core/when when `(fn [] ~when)))))

(defn log [tag id msg]
  (println (format "  %-6s %-18s %s" (name tag) (name id) msg)))

(defn converge
  "Run all steps; returns the failure count. probes maps plane -> (fn)->bool,
  consulted once per plane."
  [{:keys [check-only probes]}]
  (let [plane-up? (memoize (fn [plane] (if-let [f (get probes plane)] (f) true)))
        fails (atom 0)]
    (doseq [{:keys [id doc plane check apply applies?]} @steps]
      (try
        (cond
          (not (plane-up? plane))
          (log :skip id (str doc " — " (name plane) " unreachable"))

          (and applies? (not (applies?)))
          (log :na id (str doc " — not applicable to this device"))

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
