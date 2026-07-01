(ns tsumugu.kotoba
  "Wire a `tsumugu.store/KotobaStore` to a kotoba-server pod (e.g. kotobase.net)
  over the ai.gftd.apps.kotobase.datomic.* XRPC namespace. The store is
  unchanged — it only ever calls the `:db-api` map; `langchain.kotoba-db/
  kotoba-api` implements that map against the remote pod, so this is purely a
  constructor. Ported from `itonami.kotoba` (keep in sync).

  I/O is injected (langchain's host-caps contract): an http-fn is provided
  here via JDK java.net.http (no dependency), and the JSON pair is passed by
  the caller (e.g. clojure.data.json) so this namespace stays dependency-free.

  tsumugu is depth-1 self-sovereign: it always connects with `:identity`
  (never a handed :token), self-minting a `:cap/transact` CACAO scoped to its
  own graph — no owner hand-off, no shared secret."
  (:require [clojure.string :as str]
            [langchain.kotoba-db :as kdb]
            [tsumugu.cacao :as cacao]
            [tsumugu.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn kotoba-store
  "A `tsumugu.store/DatomicStore` backed by a kotoba-server pod (e.g.
   kotobase.net), self-sovereign via `identity` (see `tsumugu.cacao/
   load-or-create-identity!`) — tsumugu SELF-MINTS a CACAO for `:grant`
   (default a transact grant on its own graph). This is the charter path:
   no handed token, the agent issues its own capability.

   Addresses the tenant database by `db-name` (via `langchain.kotoba-db/
   kotoba-conn*`) — the wire shape the live edge's tenant Datom *write*
   requires (it derives + verifies `kotobase/db/<did>/<db-name>` from the
   CACAO's own DID server-side; a client-supplied raw `graph` is rejected
   for tenant writes). Also passes the precomputed `canonical-graph` CID as
   `:graph` — the live edge's *read* ops (`q`/`pull`/`entid`) need this
   instead (empirically, `db_name` alone matches nothing on a read: 200 OK,
   empty rows, no error). The CACAO's own resource scope is this same
   `canonical-graph` CID (what the edge independently recomputes and checks
   the signature against for both directions).

   opts:
     :url     pod base URL     :db-name target database (default: `default-db-name`)
     :json-write :json-read    injected JSON fns (e.g. data.json)
     :grant   {:cap :cap/read|:cap/transact :scope graph} (default transact on own graph)
     :http-fn optional override (defaults to jvm-http-fn)"
  [{:keys [url db-name json-write json-read identity grant http-fn]}]
  (let [db-name (or db-name cacao/default-db-name)
        graph   (cacao/canonical-graph (:did identity) db-name)
        now     (str (Instant/now))
        g       (or grant {:cap :cap/transact :scope graph})
        mint-cacao (cacao/mint identity g {:aud url :nonce (str (UUID/randomUUID))
                                           :issued-at now
                                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        host-caps {:http-fn (or http-fn jvm-http-fn)
                   :json-write json-write :json-read json-read}
        api  (kdb/kotoba-api host-caps)
        conn (kdb/kotoba-conn* url db-name {:cacao mint-cacao :did (:did identity) :graph graph})]
    (store/->DatomicStore api conn)))
