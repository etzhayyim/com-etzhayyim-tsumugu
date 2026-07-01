(ns tsumugu.cacao-test
  "Offline verification of agent-side CACAO minting (ported from
  itonami.cacao-test — keep in sync). Server acceptance can't be tested here,
  but the crypto + encoding are fully checkable: canonical did:key, a
  verifying Ed25519 signature over the exact SIWE message, and a well-formed
  CBOR envelope."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tsumugu.cacao :as c])
  (:import [java.util Base64]
           [java.security Signature]))

(deftest did-key-is-canonical-ed25519
  (let [{:keys [did]} (c/generate-identity)]
    (is (re-matches #"did:key:z6Mk[1-9A-HJ-NP-Za-km-z]+" did)
        "Ed25519 did:key has the multicodec 0xED01 prefix → z6Mk…")))

(deftest identity-round-trips
  (let [id  (c/generate-identity)
        id2 (c/load-identity id)]
    (is (= (:did id) (:did id2)) "reloaded key yields the same did")))

(deftest minted-cacao-signature-verifies
  (testing "the Ed25519 signature is over the exact SIWE message"
    (let [id    (c/generate-identity)
          grant {:cap :cap/read :scope "tsumugu"}
          opts  {:aud "https://kotobase.net" :nonce "n1" :issued-at "2026-06-27T00:00:00Z"}
          payload (c/grant->payload grant (assoc opts :iss (:did id)))
          msg   (.getBytes ^String (c/siwe-message payload) "UTF-8")
          sig   (let [s (doto (Signature/getInstance "Ed25519") (.initSign (:private-key id)))]
                  (.update s msg) (.sign s))]
      (is (c/verify? (:public-key id) msg sig)))))

(deftest minted-cacao-is-wellformed-cbor
  (let [id    (c/generate-identity)
        cacao (c/mint id {:cap :cap/transact :scope "tsumugu"}
                      {:aud "https://kotobase.net" :nonce "n2"
                       :issued-at "2026-06-27T00:00:00Z" :expiry "2026-06-27T01:00:00Z"})
        bytes (.decode (Base64/getDecoder) cacao)]
    (is (= 0xA3 (bit-and (aget bytes 0) 0xff)) "top-level CBOR is map(3) = {h,p,s}")
    (is (pos? (count cacao)))))

(deftest graph-cid-format
  (testing "CIDv1/dag-cbor/sha2-256 graph handle is base32 'bafyrei…'"
    ;; Same input string as kotobase.cid-test's graph-cid-format (CLJS) — if
    ;; this JVM port is byte-identical to the kotobase.net edge, it MUST
    ;; produce the exact same 59-char CID for the exact same name.
    (let [g (c/graph-cid-from-name "kotobase/db/did:key:zTest/people")]
      (is (str/starts-with? g "bafyrei"))
      (is (= 59 (count g))))))

(deftest graph-cid-deterministic
  (let [a (c/canonical-graph "did:key:zABC" "people")
        b (c/canonical-graph "did:key:zABC" "people")
        c (c/canonical-graph "did:key:zABC" "places")]
    (is (= a b) "same name → same CID")
    (is (not= a c) "different db-name → different CID")))

(deftest generated-identity-graph-is-canonical
  (let [{:keys [did graph]} (c/generate-identity)]
    (is (= graph (c/canonical-graph did c/default-db-name))
        "identity's :graph is canonical-graph(did, default-db-name)")))

(deftest per-actor-key-persists
  (testing "load-or-create! generates once, then reloads the same identity"
    (let [p  (str (System/getProperty "java.io.tmpdir") "/tsumugu-id-test-" (System/nanoTime) ".edn")
          a  (c/load-or-create-identity! p)
          b  (c/load-or-create-identity! p)]
      (is (= (:did a) (:did b)))
      (is (= (:graph a) (:graph b)) "stable per-actor graph across runs")
      (.delete (java.io.File. p)))))

(deftest resources-encode-capability-and-graph
  (is (= ["kotoba://op/datom:read" "kotoba://graph/tsumugu"]
         (c/grant->resources {:cap :cap/read :scope "tsumugu"})))
  (is (= ["kotoba://op/datom:transact" "kotoba://graph/g1"]
         (c/grant->resources {:cap :cap/transact :scope "g1"}))))
