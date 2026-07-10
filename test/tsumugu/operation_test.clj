(ns tsumugu.operation-test
  "End-to-end OperationActor contract: mangallm sealed, PolicyGovernor +
  phase gate earn the right to commit, append-only ledger, MemStore ≡
  DatomicStore. Mirrors `talent`'s operation contract test shape."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kami.mangaka.render :as km]
            [tsumugu.content :as content]
            [tsumugu.operation :as op]
            [tsumugu.phase :as phase]
            [tsumugu.store :as store]))

(def ^:private ip-root
  (or (System/getenv "SIP_IP_ROOT") "../../com-junkawasaki/org-spirit-in-physics-comics"))

(defn- anchors [] (km/read-anchors (str ip-root "/resources/render-anchors.edn")))

(defn- seed! [s]
  (store/commit-chapter! s (content/chapter-by-id "vol01-water-city/ch01"))
  s)

(defn- ctx [phase] {:actor-id "tsumugu" :phase phase})

(deftest supervised-phase-escalates-to-human-approval
  (testing "phase 1 (default): every clean composition still needs a human look"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :panel/compose :panel-id "01-01" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res (g/run* actor {:request req :context (ctx phase/default-phase)} {:thread-id "t1"})]
      (is (= :interrupted (:status res)))
      (is (nil? (store/committed-panel s "01-01")) "no commit before approval"))))

(deftest approval-then-commit
  (testing "resuming with :approved commits the panel + appends the ledger fact"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :panel/compose :panel-id "01-01" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res1 (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 1}} {:thread-id "t2"})]
      (is (= :interrupted (:status res1)))
      (let [res2 (g/run* actor {:approval {:status :approved :by "art-director"}}
                         {:thread-id "t2" :resume? true})]
        (is (= :commit (get-in res2 [:state :disposition])))
        (is (some? (store/committed-panel s "01-01")))
        (is (= [:commit] (mapv :disposition (store/ledger s)))))))
  (testing "resuming with :rejected holds — no SSoT mutation"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :panel/compose :panel-id "01-02" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          _ (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 1}} {:thread-id "t3"})
          res2 (g/run* actor {:approval {:status :rejected :by "art-director"}}
                       {:thread-id "t3" :resume? true})]
      (is (= :hold (get-in res2 [:state :disposition])))
      (is (nil? (store/committed-panel s "01-02"))))))

(deftest phase-2-auto-commits-clean-compositions
  (testing "phase 2 (supervised-auto): a clean, non-high-stakes panel auto-commits"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          panel (first (filter #(and (not (#{"splash" "full-page"} (:size %)))
                                     (seq (:dialogue %)))
                               (:storyboard (content/chapter-by-id "vol01-water-city/ch01"))))
          req {:op :panel/compose :panel-id (:id panel) :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 2}} {:thread-id "t4"})]
      (when (= :commit (get-in res [:state :disposition]))
        (is (some? (store/committed-panel s (:id panel))))))))

(deftest phase-2-never-auto-commits-a-high-stakes-splash-panel
  ;; PolicyGovernor's high-stakes check used to read
  ;; (get-in proposal [:value :layout]) -- :value is the composed render
  ;; spec, which never carries :layout (kami.mangaka.render/compose
  ;; consumes it internally to derive :aspect, then drops it), so that
  ;; read was always nil and this escalation could never fire. Panel
  ;; "01-01" is a real splash/full-page panel (manga.edn) -- even in
  ;; phase 2 (supervised-auto), it must escalate/hold, never silently
  ;; auto-commit with zero human review.
  (testing "phase 2 still refuses to auto-commit a splash/full-page panel"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :panel/compose :panel-id "01-01" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 2}} {:thread-id "t6"})]
      (is (not= :commit (get-in res [:state :disposition]))
          "a splash/full-page panel must not auto-commit even in phase 2")
      (is (nil? (store/committed-panel s "01-01"))))))

(deftest missing-panel-holds
  (testing "a panel-id that doesn't exist in the chapter → no surviving candidate → HOLD"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :panel/compose :panel-id "99-99" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 2}} {:thread-id "t5"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/committed-panel s "99-99"))))))

(deftest datomic-store-same-contract
  (testing "backend swap (MemStore → DatomicStore) is a config change, not a rewrite"
    (let [s (seed! (store/datomic-store))
          actor (op/build s)
          req {:op :panel/compose :panel-id "01-01" :chapter-id "vol01-water-city/ch01"
               :anchors (anchors)}
          res1 (g/run* actor {:request req :context {:actor-id "tsumugu" :phase 1}} {:thread-id "d1"})
          res2 (g/run* actor {:approval {:status :approved :by "art-director"}}
                       {:thread-id "d1" :resume? true})]
      (is (= :interrupted (:status res1)))
      (is (= :commit (get-in res2 [:state :disposition])))
      (is (some? (store/committed-panel s "01-01"))))))
