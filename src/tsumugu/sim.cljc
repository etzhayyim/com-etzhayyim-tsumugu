(ns tsumugu.sim
  "Demo runner: push a few Spirit in Physics panels through one
  OperationActor and watch the PolicyGovernor + coscientist tournament +
  approval workflow earn mangallm the right to commit.

    op1  第1話 01-01（静物パネル・splash） → high-stakes → 人間承認へ escalate
                                            → art-director approve → commit
    op2  第1話 01-13b（対話パネル）        → phase 2 なら policy-clean auto-commit
    op3  存在しないパネルID                → coscientist tournament 不成立 → hold

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [kami.mangaka.render :as km]
            [tsumugu.content :as content]
            [tsumugu.operation :as op]
            [tsumugu.store :as store]))

(defn- line [& xs] (println (apply str xs)))

(defn- anchors []
  (km/read-anchors (str content/ip-root "/resources/render-anchors.edn")))

(defn- run-op!
  "Run one panel-composition operation on its own thread-id. If it interrupts
  for human approval, the art director 'approves' and we resume — mirroring a
  real approval workflow."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  承認ワークフロー — art director がレビュー中 (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "art-director"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  承認" (if approve? "可決" "却下") " → disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [s (store/seed-db)
        ch (content/chapter-by-id "vol01-water-city/ch01")
        _ (store/commit-chapter! s ch)
        actor (op/build s)
        director {:actor-id "tsumugu"}
        an (anchors)]

    (line "── Spirit in Physics: 第" (:chapter ch) "話「" (:title ch) "」 ("
          (count (:storyboard ch)) " panels) ──")

    (line "\n── OperationActor (mangallm sealed; PolicyGovernor active) ──")

    (line "\nop1  01-01（splash、静物） — phase 1: 常に人間承認へ")
    (run-op! actor "op1"
             {:op :panel/compose :panel-id "01-01" :chapter-id (:id ch) :anchors an}
             (assoc director :phase 1) true)

    (line "\nop2  01-13b（対話パネル） — phase 2: policy-clean なら自動commit")
    (run-op! actor "op2"
             {:op :panel/compose :panel-id "01-13b" :chapter-id (:id ch) :anchors an}
             (assoc director :phase 2) true)

    (line "\nop3  存在しないパネルID — coscientist tournament 不成立 → hold")
    (run-op! actor "op3"
             {:op :panel/compose :panel-id "99-99" :chapter-id (:id ch) :anchors an}
             (assoc director :phase 2) true)

    (line "\n── 監査台帳 (append-only; 全ての panel commit/hold の不変の証跡) ──")
    (doseq [f (store/ledger s)]
      (line "  " (:disposition f) " · panel=" (or (:panel f) "?")
            " · op=" (:op f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-store)
          _ (store/commit-chapter! ds ch)
          dactor (op/build ds)]
      (run-op! dactor "d1"
               {:op :panel/compose :panel-id "01-01" :chapter-id (:id ch) :anchors an}
               (assoc director :phase 1) true)
      (line "  DatomicStore ledger = " (mapv :disposition (store/ledger ds))))

    (line "\ndone.")))
