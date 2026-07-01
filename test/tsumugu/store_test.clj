(ns tsumugu.store-test
  "Store contract against both backends — proving MemStore ≡ DatomicStore makes
  'swap the SSoT for kotoba-server' a config change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [tsumugu.store :as store]))

(def ^:private ch01
  {:id "vol01-water-city/ch01" :chapter 1 :title "第1話 配属の日"
   :storyboard [{:id "01-01" :size "splash" :description "朝の運河"}
                {:id "01-02" :size "medium" :description "タマキが振り返る"}]})

(defn- backends []
  (let [mem (store/seed-db) dat (store/datomic-store)]
    (store/commit-chapter! mem ch01)
    (store/commit-chapter! dat ch01)
    [["MemStore" mem] ["DatomicStore" dat]]))

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "第1話 配属の日" (:title (store/chapter s "vol01-water-city/ch01"))))
      (is (= 1 (:chapter (store/chapter s "vol01-water-city/ch01"))))
      (is (= ["01-01" "01-02"] (mapv :id (store/panels-of s "vol01-water-city/ch01"))))
      (is (nil? (store/chapter s "missing")))
      (is (nil? (store/committed-panel s "01-01"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/commit-panel! s "01-01" {:prompt "watercolor wash, canal" :seed 4242})
      (is (= 4242 (:seed (store/committed-panel s "01-01"))))
      (store/append-ledger! s {:op :commit-panel :panel "01-01" :disposition :commit})
      (store/append-ledger! s {:op :hold :panel "01-02" :disposition :hold})
      (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))))

(deftest datomic-empty-store-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/chapter s "nope")))
    (is (= [] (store/all-chapters s)))
    (store/commit-chapter! s ch01)
    (is (= "第1話 配属の日" (:title (store/chapter s "vol01-water-city/ch01"))))))
