(ns tsumugu.store
  "SSoT for the tsumugu actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite (MemStore default ‖ DatomicStore via langchain.db,
  itself swappable to real Datomic Local / kotoba-server).

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC, e.g. kotobase.net)
  both implement it, so the same `DatomicStore` record runs on either by
  construction (see `tsumugu.kotoba/kotoba-store`).

  Domain: chapter (episode/storyboard, read-only from org-spirit-in-physics-
  comics) → panel (committed render payload, one per storyboard panel-id).
  The append-only ledger is the publish provenance — every panel commit is a
  fact in an immutable log, never overwritten."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (chapter [s id])
  (all-chapters [s])
  (panels-of [s chapter-id])
  (committed-panel [s id] "the committed render payload for a panel, or nil")
  (ledger [s])
  (commit-chapter! [s chapter] "seed/replace one chapter's storyboard")
  (commit-panel! [s panel-id payload] "commit a panel's final render payload")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (chapter [_ id] (get-in @a [:chapters id]))
  (all-chapters [_] (sort-by :chapter (vals (:chapters @a))))
  (panels-of [_ chapter-id] (get-in @a [:chapters chapter-id :storyboard] []))
  (committed-panel [_ id] (get-in @a [:panels id]))
  (ledger [_] (:ledger @a))
  (commit-chapter! [s ch] (swap! a assoc-in [:chapters (:id ch)] ch) s)
  (commit-panel! [s id payload] (swap! a assoc-in [:panels id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore (chapters seeded by the caller via `commit-chapter!`)."
  []
  (->MemStore (atom {:chapters {} :panels {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:gh.tsumugu.chapter/id     {:db/unique :db.unique/identity}
   :gh.tsumugu.panel/id       {:db/unique :db.unique/identity}
   :gh.tsumugu.ledger/seq     {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net)
;; both implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (chapter [this id]
    ;; q-only (not pull*) — some kotoba-server pods don't implement the
    ;; datomic.pull XRPC op at all (404 MethodNotImplemented).
    (when-let [[number title storyboard]
               (first (q* this '[:find ?number ?title ?storyboard :in $ ?id
                                 :where [?e :gh.tsumugu.chapter/id ?id]
                                        [?e :gh.tsumugu.chapter/number ?number]
                                        [?e :gh.tsumugu.chapter/title ?title]
                                        [?e :gh.tsumugu.chapter/storyboard ?storyboard]]
                         id))]
      {:id id :chapter number :title title :storyboard (or (dec* storyboard) [])}))
  (all-chapters [this]
    (->> (q* this '[:find [?id ...] :where [?e :gh.tsumugu.chapter/id ?id]])
         (map #(chapter this %)) (sort-by :chapter)))
  (panels-of [this id] (:storyboard (chapter this id)))
  (committed-panel [this id]
    (dec* (q* this '[:find ?p . :in $ ?pid
                     :where [?e :gh.tsumugu.panel/id ?pid] [?e :gh.tsumugu.panel/payload ?p]]
               id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :gh.tsumugu.ledger/seq ?s] [?e :gh.tsumugu.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-chapter! [s {:keys [id chapter title storyboard]}]
    (tx* s [(cond-> {:gh.tsumugu.chapter/id id}
              chapter    (assoc :gh.tsumugu.chapter/number chapter)
              title      (assoc :gh.tsumugu.chapter/title title)
              storyboard (assoc :gh.tsumugu.chapter/storyboard (enc storyboard)))])
    s)
  (commit-panel! [s id payload]
    (tx* s [{:gh.tsumugu.panel/id id :gh.tsumugu.panel/payload (enc payload)}])
    s)
  (append-ledger! [s fact]
    (tx* s [{:gh.tsumugu.ledger/seq (count (ledger s)) :gh.tsumugu.ledger/fact (enc fact)}])
    fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (default Datomic-
  shaped store; verifiable offline, no network). For the kotoba-server pod
  (kotobase.net), see `tsumugu.kotoba/kotoba-store` — same record, different
  `:db-api`."
  []
  (->DatomicStore d/api (d/create-conn schema)))
