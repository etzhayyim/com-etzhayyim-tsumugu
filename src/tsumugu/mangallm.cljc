(ns tsumugu.mangallm
  "mangallm — the *contained intelligence node*: it drafts a panel
  composition and returns a PROPOSAL, never a committed record. Wraps
  `tsumugu.coscientist`'s full generate → surviving → rank → evolve →
  meta-review tournament plus `tsumugu.render/compose`; every output is
  censored downstream by `tsumugu.policy` before anything touches the SSoT.
  Mirrors `talent.hrllm`'s Advisor protocol shape.

  Proposal shape:
    {:summary    str   ; human-facing description of the chosen composition
     :rationale  str   ; the coscientist meta-review's pattern sentence
     :cites      [kw…] ; panel fields the winning strategy drew from
     :effect     :commit-panel | :noop
     :value      {…}   ; the composed render spec (:tags :prompt :neg :aspect)
     :layout     str   ; the panel's page-level layout (e.g. \"splash\") --
                        ; NOT part of :value (the render spec never carries
                        ; it; :layout is consumed internally by
                        ; tsumugu.render/compose to derive :aspect and
                        ; otherwise dropped), so tsumugu.policy's high-stakes
                        ; check needs it surfaced here directly
     :size       str   ; the panel's own size (e.g. \"full-page\"), same
                        ; reason as :layout above
     :panel-id   str
     :confidence 0..1  ; survivor fraction of the tournament (0 → :noop)}"
  (:require [tsumugu.coscientist :as cosci]
            [tsumugu.store :as store]))

(defn compose-panel
  "Full coscientist pipeline for one `panel`: generate → surviving → rank →
  evolve (re-reviewed; falls back to the top-ranked survivor if the evolved
  recombination doesn't itself pass review) → the winning composed spec.
  Returns {:composed :strategy :ranked} or nil if nothing survived."
  [ctx panel]
  (let [survivors (cosci/surviving ctx (cosci/generate panel))
        ranked (cosci/rank survivors)]
    (when (seq ranked)
      (let [evolved (cosci/evolve ranked)
            evolved-review (when evolved (cosci/review ctx evolved))
            winner (if (and evolved-review (:ok? evolved-review))
                     (assoc evolved :review evolved-review)
                     (first ranked))]
        {:composed (get-in winner [:review :composed])
         :strategy (:strategy winner)
         :ranked ranked}))))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap. Either way its output is a PROPOSAL the
;; PolicyGovernor still censors — the single invariant never depends on which
;; advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn- advise* [st {:keys [panel-id chapter-id anchors]}]
  (let [ch (store/chapter st chapter-id)
        panel (and ch (first (filter #(= panel-id (:id %)) (:storyboard ch))))]
    (cond
      (nil? ch)
      {:summary (str "chapter " chapter-id " not found") :rationale "no such chapter"
       :cites [] :effect :noop :panel-id panel-id :confidence 0.0}

      (nil? panel)
      {:summary (str "panel " panel-id " not found in chapter " chapter-id)
       :rationale "no such panel in the storyboard" :cites [] :effect :noop
       :panel-id panel-id :confidence 0.0}

      :else
      (let [ctx {:anchors anchors :panel panel}
            result (compose-panel ctx panel)]
        (if-not result
          {:summary (str "panel " panel-id ": no surviving composition")
           :rationale "every coscientist candidate was rejected by charter review"
           :cites [] :effect :noop :panel-id panel-id :confidence 0.0}
          (let [{:keys [composed strategy ranked]} result
                mr (cosci/meta-review ranked panel)]
            {:summary (str "panel " panel-id " composed via '" strategy "'")
             :rationale (:pattern mr)
             :cites (vec (remove nil? [:description
                                       (when (seq (:dialogue panel)) :dialogue)
                                       (when (seq (:colorNote panel)) :colorNote)]))
             :effect :commit-panel
             :value composed
             :layout (:layout panel)
             :size (:size panel)
             :panel-id panel-id
             :confidence (min 1.0 (/ (double (count ranked)) (double (count cosci/catalog))))}))))))

(defn mock-advisor
  "The deterministic coscientist-backed advisor. Default everywhere — there
  is no non-deterministic LLM free-write in this pipeline (see
  `tsumugu.coscientist`'s docstring: candidates are a closed catalog)."
  []
  (reify Advisor (-advise [_ st req] (advise* st req))))

(defn trace
  "Decision-grounded audit record — the tournament's interpretable rationale
  (evaluation appeals, publish audits)."
  [request proposal]
  {:t          :mangallm-proposal
   :op         (:op request)
   :panel      (:panel-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
