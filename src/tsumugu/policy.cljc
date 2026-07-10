(ns tsumugu.policy
  "PolicyGovernor — the independent censor that earns mangallm's coscientist
  proposal the right to commit. The coscientist already enforces its OWN
  charter gates per-candidate (G-mechanism/G-style/G-canon/G-budget, see
  `tsumugu.coscientist/review`); this is a SEPARATE system checking the
  proposal as a whole — defense in depth, never trusting the generator to
  have been the only gate. Ported from `talent.policy`'s shape (RBAC/
  confidence-floor/high-stakes → HOLD or ESCALATE, never auto-override).

  Two checks, in priority order. The first is HARD (a human approver cannot
  override it — there is nothing to approve, the tournament produced
  nothing). The second is SOFT (asks a human to look; they may approve).

    1. No surviving candidate  — the coscientist tournament rejected every
                                 candidate → HOLD (nothing to commit).
    2. Confidence / high-stakes — low tournament confidence, or a
                                 high-stakes layout (splash/full-page,
                                 double-page spreads carry more weight in a
                                 reader's eye) → ESCALATE for human review.")

(def confidence-floor 0.4)

(def high-stakes-layouts
  "Page-layout (\"splash\") or panel-size (\"full-page\") values weighty
  enough to always want a human look, even when the tournament was
  clean -- checked against both the panel's :layout and :size (see
  `check` below)."
  #{"splash" "full-page"})

(defn check
  "Censors a mangallm proposal. Returns {:ok? bool :violations [..]
  :confidence c :escalate? bool :hard? bool :high-stakes? bool}."
  [_request _context proposal]
  (let [hard (cond-> []
               (= :noop (:effect proposal))
               (conj {:rule :no-surviving-candidate
                      :detail "coscientist tournament had no survivors — nothing to commit"}))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        ;; NOT (get-in proposal [:value :layout]) -- :value is the composed
        ;; render spec ({:tags :prompt :neg :refs :aspect :dims}, see
        ;; kami.mangaka.render/compose), which never carries :layout: the
        ;; panel's :layout is consumed internally to derive :aspect and
        ;; then dropped. That old read was always nil, so this high-stakes
        ;; escalation could never fire -- a splash/full-page panel could
        ;; auto-commit with zero human review, contradicting this file's
        ;; own docstring and the repo's CLAUDE.md invariant. :layout/:size
        ;; are now surfaced directly on the proposal (mangallm.cljc).
        stakes? (boolean (or (contains? high-stakes-layouts (:layout proposal))
                             (contains? high-stakes-layouts (:size proposal))))
        hard? (boolean (seq hard))]
    {:ok?         (and (not hard?) (not low?) (not stakes?))
     :violations  hard
     :confidence  conf
     :hard?       hard?
     :escalate?   (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :panel      (:panel-id request)
   :actor      (:actor-id context)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
