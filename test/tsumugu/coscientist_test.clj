(ns tsumugu.coscientist-test
  "Coscientist quality loop against real Spirit in Physics anchors + a real
  storyboard panel (requires $SIP_IP_ROOT / the default relative checkout)."
  (:require [clojure.test :refer [deftest is]]
            [kami.mangaka.render :as km]
            [tsumugu.coscientist :as cosci]
            [tsumugu.content :as content]))

(def ^:private ip-root
  (or (System/getenv "SIP_IP_ROOT") "../../com-junkawasaki/org-spirit-in-physics-comics"))

(defn- anchors [] (km/read-anchors (str ip-root "/resources/render-anchors.edn")))

(defn- a-panel []
  (let [ch (content/chapter-by-id "vol01-water-city/ch01")]
    (first (filter #(seq (:dialogue %)) (:storyboard ch)))))

(deftest generate-is-deterministic-and-closed
  (let [panel (a-panel) hyps (cosci/generate panel)]
    (is (<= (count hyps) 5))
    (is (every? #(contains? cosci/aligned-strategies (:strategy %)) hyps)
        "every generated candidate uses a closed, aligned strategy")
    (is (= hyps (cosci/generate panel)) "pure — same input, same output")))

(deftest review-rejects-forbidden-strategy
  (let [ctx {:anchors (anchors) :panel (a-panel)}
        hyp {:id "x" :strategy "character-swap" :bonus-tags []}]
    (is (false? (:ok? (cosci/review ctx hyp))))
    (is (some #(re-find #"G-mechanism" %) (:reasons (cosci/review ctx hyp))))))

(deftest review-enforces-style-lead-and-budget
  (let [ctx {:anchors (anchors) :panel (a-panel)}]
    (doseq [hyp (cosci/generate (a-panel))]
      (let [v (cosci/review ctx hyp)]
        (when (:ok? v)
          (is (<= (count (get-in v [:composed :tags])) (:word-budget (anchors)))
              "surviving candidates never exceed the CLIP word budget"))))))

(deftest rank-is-a-total-deterministic-order
  (let [ctx {:anchors (anchors) :panel (a-panel)}
        survivors (cosci/surviving ctx (cosci/generate (a-panel)))
        ranked (cosci/rank survivors)]
    (is (= (count survivors) (count ranked)))
    (is (apply >= (map :elo ranked)) "sorted by Elo descending")
    (is (= ranked (cosci/rank survivors)) "reproducible tournament")))

(deftest evolve-recombines-top-two
  (let [ctx {:anchors (anchors) :panel (a-panel)}
        ranked (cosci/rank (cosci/surviving ctx (cosci/generate (a-panel))))
        evolved (cosci/evolve ranked)]
    (when (>= (count ranked) 2)
      (is (:evolved evolved))
      (is (<= (count (:bonus-tags evolved)) 4)))))

(deftest meta-review-fails-open-to-template
  (let [ctx {:anchors (anchors) :panel (a-panel)}
        ranked (cosci/rank (cosci/surviving ctx (cosci/generate (a-panel))))
        mr (cosci/meta-review ranked (a-panel))]
    (is (= "template" (:via mr)))
    (is (string? (:pattern mr)))))
