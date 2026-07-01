(ns tsumugu.coscientist
  "coscientist — before a panel commits, propose K candidate compositions,
  critique them against a charter, run a deterministic Elo tournament, evolve
  the winners. Ported from ibuki's `methods/coscientist.cljc` (ADR-2606201200,
  the Google 'AI co-scientist' shape: Generate → Reflect → Rank → Evolve →
  Meta-review) — the loop shape is domain-agnostic; only the catalog + charter
  gates are manga-specific here.

  Domain: a candidate is a *bonus-tag extraction strategy* — which of the
  storyboard panel's free-text fields (description / colorNote / narration /
  emotion) gets distilled into a few extra booru tags appended to the base
  composition (`tsumugu.render/compose`), within the CLIP word budget. Never
  an LLM free-write: extraction is a fixed, deterministic text-processing
  function, so a hallucinated or off-canon tag is structurally
  unrepresentable (G-mechanism).

  EVERYTHING here is deterministic + pure (no wall clock, no randomness, no
  network I/O — the actual image-gen render happens only after the tournament
  picks a winner) so the panel ledger is content-addressable and the
  tournament is reproducible."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [kami.mangaka.render :as km]
            [tsumugu.render :as render]))

;; ── strategy vocabulary (closed) ────────────────────────────────────────────

(def aligned-strategies
  "The only ways a candidate may add bonus tags. Each reads ONLY the panel's
  own text — a strategy outside this set cannot enter the tournament
  (G-mechanism)."
  #{"colorNote-lead" "narration-lead" "description-lead" "balanced" "style-only"})

(def forbidden-strategies
  "Unrepresentable: the generator never emits these and `review` rejects them
  on sight — the ways a hostile/hallucinated generator would corrupt canon."
  #{"nsfw-tag-injection" "copyright-character-splice" "off-canon-substitution"
    "character-swap"})

(def catalog
  [{:id "colorNote-lead"   :strategy "colorNote-lead"   :source :colorNote}
   {:id "narration-lead"   :strategy "narration-lead"   :source :narration}
   {:id "description-lead" :strategy "description-lead" :source :description}
   {:id "balanced"         :strategy "balanced"         :source :balanced}
   {:id "style-only"       :strategy "style-only"       :source nil}])

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- clamp01 [x] (max 0.0 (min 1.0 (double x))))

(defn- text-tags
  "Coarse booru-ish tag extraction from a free-text field: split on
  Japanese/ASCII separators, keep short (<=6 char) tokens, dedupe, cap at 3.
  Pure, no LLM — a fixed function, so a hallucinated tag is structurally
  unrepresentable."
  [s]
  (->> (str/split (str s) #"[、。・\s,]+")
       (remove str/blank?)
       (filter #(<= (count %) 6))
       distinct
       (take 3)
       vec))

(defn- bonus-tags [panel source]
  (case source
    :balanced (vec (distinct (concat (text-tags (:colorNote panel)) (text-tags (:emotion panel)))))
    nil       []
    (text-tags (get panel source))))

;; ── GENERATE ────────────────────────────────────────────────────────────────

(defn generate
  "Propose K candidate bonus-tag sets for `panel`, one per catalog strategy,
  scaled by any learned per-strategy `weights` (mechanisms that paid off
  before are amplified). Deterministic."
  ([panel] (generate panel {}))
  ([panel {:keys [weights k] :or {weights {} k 5}}]
   (->> catalog
        (map (fn [{:keys [source] :as arch}]
               (let [bonus (bonus-tags panel source)
                     w (double (get weights (:strategy arch) 1.0))]
                 (assoc arch
                        :bonus-tags bonus
                        :expected-richness (clamp01 (* (/ (count bonus) 3.0) w))
                        :charter-class "aligned"))))
        (sort-by (fn [h] [(- (:expected-richness h)) (:id h)]))
        (take k)
        vec)))

;; ── REFLECT (review against the charter) ────────────────────────────────────

(defn- composed-with-bonus
  "Recompose `panel` with `bonus-tags` appended at the lowest priority (after
  quality-tail), re-applying the commons' own word-budget cutoff — a bonus tag
  that doesn't fit is simply dropped, never silently over-budget."
  [{:keys [anchors panel]} bonus-tags]
  (let [base (render/compose {:anchors anchors :panel panel})
        {:keys [word-budget]} anchors
        body (km/take-budget word-budget [(:tags base) (vec bonus-tags)])]
    (assoc base :tags body :prompt (str/join ", " body)
           :surviving-bonus (vec (filter (set body) bonus-tags)))))

(defn review
  "Critique ONE candidate against the charter gates. Returns {:ok? bool
  :reasons [str…] :composed}. G-mechanism (unaligned/forbidden strategy),
  G-style (style-lead must still lead after recomposition), G-canon (no bonus
  tag may collide with another character's canon tags — no character bleed),
  G-budget (composed prompt must not exceed word-budget)."
  [{:keys [anchors] :as ctx} hyp]
  (let [strat (:strategy hyp)
        composed (composed-with-bonus ctx (:bonus-tags hyp))
        other-chars (remove #{(render/focal-character (:panel ctx))} (keys (:characters anchors)))
        other-tags (set (mapcat #(get-in anchors [:characters % :tags] []) other-chars))
        style-lead (:style-lead anchors)
        leads? (= (take (count style-lead) (:tags composed)) style-lead)
        reasons
        (cond-> []
          (not (contains? aligned-strategies strat))
          (conj (str "strategy not aligned/unrepresentable: " (pr-str strat)))
          (contains? forbidden-strategies strat)
          (conj (str "forbidden strategy (G-mechanism): " strat))
          (not leads?)
          (conj "style-lead no longer leads the composed prompt (G-style)")
          (seq (set/intersection (set (:surviving-bonus composed)) other-tags))
          (conj "bonus tag collides with another character's canon (G-canon)")
          (> (count (:tags composed)) (:word-budget anchors))
          (conj "composed prompt exceeds word budget (G-budget)"))]
    {:ok? (empty? reasons) :reasons reasons :composed composed}))

(defn surviving
  "Candidates that pass `review`, each annotated with its review. Pure."
  [ctx hyps]
  (->> hyps
       (map (fn [h] (assoc h :review (review ctx h))))
       (filter (fn [h] (get-in h [:review :ok?])))
       vec))

;; ── RANK (a deterministic Elo tournament) ───────────────────────────────────

(defn utility
  "Fitness = surviving bonus-tag richness. Higher = a composition that added
  more grounded, on-canon detail within budget."
  [hyp]
  (double (count (get-in hyp [:review :composed :surviving-bonus] []))))

(defn- elo-update [ra rb sa]
  (let [ea (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0))))]
    (+ ra (* 32.0 (- sa ea)))))

(defn rank
  "Pairwise Elo tournament over `hyps`: every ordered pair plays, the
  higher-utility hypothesis wins (deterministic tie-break by :id). Returns
  hyps sorted by Elo desc, each carrying :utility and :elo. Pure + reproducible."
  [hyps]
  (let [scored (mapv (fn [h] (assoc h :utility (utility h))) hyps)
        ids (mapv :id scored)
        init (zipmap ids (repeat 1000.0))
        elos (reduce
              (fn [elo [a b]]
                (let [ha (first (filter #(= (:id %) a) scored))
                      hb (first (filter #(= (:id %) b) scored))
                      ua (:utility ha) ub (:utility hb)
                      sa (cond (> ua ub) 1.0 (< ua ub) 0.0
                               :else (if (neg? (compare a b)) 1.0 0.0))
                      ra (elo a) rb (elo b)]
                  (-> elo
                      (assoc a (elo-update ra rb sa))
                      (assoc b (elo-update rb ra (- 1.0 sa))))))
              init
              (for [a ids b ids :when (neg? (compare a b))] [a b]))]
    (->> scored
         (map (fn [h] (assoc h :elo (get elos (:id h)))))
         (sort-by (fn [h] [(- (:elo h)) (:id h)]))
         vec)))

;; ── EVOLVE + META-REVIEW ────────────────────────────────────────────────────

(defn evolve
  "Recombine the top two ranked candidates' surviving bonus tags into one
  compound candidate (dedup, cap at 4). Returns the evolved candidate, or nil
  if <2 ranked. Pure."
  [ranked]
  (when (>= (count ranked) 2)
    (let [[a b] ranked]
      {:id (str "evolve-" (:id a) "+" (:id b))
       :strategy (:strategy a)
       :bonus-tags (vec (take 4 (distinct (concat (get-in a [:review :composed :surviving-bonus] [])
                                                   (get-in b [:review :composed :surviving-bonus] [])))))
       :charter-class "aligned"
       :evolved true})))

(defn meta-review
  "Extract the lesson of this panel's tournament: the winning strategy + a
  one-line pattern. `infer` (optional) is a narrator fn (prompt fallback →
  {:text :via}); absent → the deterministic template (fail-open)."
  ([ranked panel] (meta-review ranked panel nil))
  ([ranked panel infer]
   (let [winner (first ranked)
         strat (:strategy winner)
         template (str "panel " (:id panel) " — the winning composition strategy was '"
                       strat "' (surviving bonus tags: "
                       (pr-str (get-in winner [:review :composed :surviving-bonus] [])) ").")
         narr (if infer
                (infer (str "You are a manga art director reviewing panel " (:id panel)
                            ". In ONE short sentence, explain why '" strat
                            "' best serves this panel's storyboard intent."))
                {:text template :via "template"})]
     {:pattern (:text narr) :winner winner :via (:via narr)})))
