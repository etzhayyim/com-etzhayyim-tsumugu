(ns tsumugu.phase
  "Phase 0→2 staged rollout — start narrow (read-only), widen as trust grows.
  Where the PolicyGovernor answers 'is this composition allowed?', the phase
  answers 'how much autonomy does tsumugu have *yet* to publish it?'. It can
  only ever make the actor MORE conservative than policy: it downgrades a
  policy-clean commit to approval or hold, never the reverse. Ported from
  `talent.phase`.

    Phase 0  read-only        — no panel commits at all. Generation/review
                                only (shadow/observe).
    Phase 1  assisted         — panel commits allowed, but every one needs
                                human approval. (default — a new work starts
                                supervised.)
    Phase 2  supervised-auto  — policy-clean, high-confidence panels may
                                auto-commit; the rest still escalate.")

(def write-ops #{:panel/compose})

(def phases
  {0 {:label "read-only"       :writes #{}          :auto #{}}
   1 {:label "assisted"        :writes write-ops     :auto #{}}
   2 {:label "supervised-auto" :writes write-ops     :auto write-ops}})

(def default-phase 1)

(defn gate
  "Adjust a policy disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}."
  [phase {:keys [op]} policy-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold policy-disposition)     {:disposition :hold :reason nil}
      (not (contains? writes op))     {:disposition :hold :reason :phase-disabled}
      (and (= :commit policy-disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                           {:disposition policy-disposition :reason nil})))

(defn verdict->disposition
  "Map a PolicyGovernor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
