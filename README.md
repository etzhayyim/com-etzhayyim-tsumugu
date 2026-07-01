# com-etzhayyim-tsumugu

**紡ぐ (tsumugu — to spin/weave)** — the Spirit in Physics (Ghost Hacker
universe) manga publishing actor: a self-sovereign AT Protocol identity that
generates panel compositions via the `kami-mangaka-{render,page}-clj`
commons, quality-gates them through a co-scientist tournament, and commits
only what an independent PolicyGovernor + human approval clear. Built on this
workspace's [`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime — the same actor pattern as
[`gftd-talent-actor`](https://github.com/gftdcojp/gftd-talent-actor) /
[`robotaxi-actor`](https://github.com/com-junkawasaki/robotaxi-actor) /
[`ai-gftd-itonami`](https://github.com/gftdcojp/ai-gftd-itonami).

See [ADR-2607011500](../../../90-docs/adr/2607011500-ghosthacker-sip-atproto-actor.md)
for the full decision record (why relay/pull publish, not push into
app-aozora's single-owner PDS).

> **Why an actor layer at all?** A manga-generation model is great at
> drafting panel compositions — but it has no notion of canon fidelity,
> style consistency, or when a splash page is weighty enough to want a human
> look before it ships. This project seals the generator into a single node
> (`mangallm`) and wraps it with a **co-scientist quality tournament**
> (generate → charter-review → Elo rank → evolve), an independent
> **PolicyGovernor**, a human **approval workflow**, and an immutable
> **audit ledger** — every published panel is traceable to why it won.

## The core contract

```
storyboard panel (from org-spirit-in-physics-comics)
        │
        ▼
   ┌───────────┐   proposal    ┌────────────────┐
   │ mangallm  │ ────────────▶ │ PolicyGovernor │  (independent system)
   │ (sealed:  │  composition  │  confidence ·  │
   │ coscien-  │  + rationale  │  high-stakes   │
   │ tist loop)│               └───────┬────────┘
   └───────────┘          commit ◀─────┼─────▶ hold (no survivors; not overridable)
                                │              │
                          SSoT + 台帳     escalate ─▶ human approval (interrupt)
                                │
                          self-sovereign kotoba-server graph (kotobase.net)
                                │
                          ┌─────┴─────┐
                          │  fan-out  │  (kototama.emit-style targets map)
                          └─────┬─────┘
                     ┌──────────┼──────────┐
                 aozora-relay   x (future)  instagram/line (future)
                (pull/subscribe)
```

**mangallm never commits a panel the coscientist tournament didn't produce a
surviving candidate for, and the PolicyGovernor never lets a low-confidence
or high-stakes (splash/full-page) panel auto-commit without a human look.**
tsumugu holds its own Ed25519 key — its graph is `canonical-graph(did,
"manga")`, the same CIDv1/dag-cbor/sha2-256-of-name derivation the
kotobase.net edge itself recomputes from the DID alone (`tsumugu.cacao`,
ported from `kotobase.cid` — the CLJS client app-aozora already uses live —
rather than the raw-pubkey-derived IPNS scheme `itonami.cacao` uses, which
doesn't match what the real edge resolves). So publishing never depends on
`app-aozora`'s owner secret or deploy lifecycle; downstream consumers
(aozora.app first, then X/Instagram/LINE) each independently relay from
tsumugu's own graph, addressing it the same way tsumugu itself does.

## Run

```bash
clojure -M:dev:run     # demo: 3 panels through one OperationActor + backend swap
clojure -M:dev:test    # cacao crypto · store parity · coscientist tournament · operation contract
clojure -M:lint        # clj-kondo (errors fail)
```

## Layout

| File | Role |
|---|---|
| `src/tsumugu/cacao.clj` | Self-sovereign identity — Ed25519 → did:key → `canonical-graph(did, db-name)`. CACAO mint ported from `itonami.cacao`; graph derivation ported from `kotobase.cid` (byte-identical to the kotobase.net edge). |
| `src/tsumugu/kotoba.clj` | Wires a `tsumugu.store/DatomicStore` to a kotoba-server pod (kotobase.net), self-minting its own CACAO. Ported from `itonami.kotoba`. |
| `src/tsumugu/store.cljc` | `Store` protocol — MemStore (default) ‖ DatomicStore (in-process EAVT, or kotoba-server via `:db-api` swap). |
| `src/tsumugu/content.clj` | Reads chapters/storyboards from `org-spirit-in-physics-comics` ($SIP_IP_ROOT). Mirrors `sip.storyboard`. |
| `src/tsumugu/render.clj` | Work-specific composition mappers (Nei light/embodied, emotion table, location map) over the `kami.mangaka.render` commons. Mirrors `sip.render`. |
| `src/tsumugu/coscientist.cljc` | Generate → review (charter gates) → Elo rank → evolve → meta-review. Ported from `com-etzhayyim-ibuki`'s `methods/coscientist.cljc`. |
| `src/tsumugu/mangallm.cljc` | The contained intelligence node — wraps the coscientist tournament into an `Advisor` (proposal only). |
| `src/tsumugu/policy.cljc` | PolicyGovernor — no-surviving-candidate / confidence-floor / high-stakes-layout checks. |
| `src/tsumugu/phase.cljc` | 0→2 rollout gate (read-only → assisted → supervised-auto). |
| `src/tsumugu/operation.cljc` | The StateGraph: intake → advise → govern → decide → commit \| hold \| request-approval. |
| `src/tsumugu/sim.cljc` | Demo runner. |

## Status

23 tests, 62 assertions, green (`clojure -M:dev:test`). Identity (graph =
`canonical-graph(did, db-name)`, byte-identical to the kotobase.net edge —
verified against `kotobase.cid-test`'s own test vector), store (Mem ≡
Datomic parity), content-read (verified against all 62 real chapters),
coscientist tournament, and the full StateGraph (approval interrupt/resume,
phase-gated auto-commit, hold-on-no-survivors) are exercised end-to-end
against real Spirit in Physics storyboard data.

The downstream `aozora-relay` adapter (`app-aozora` polling tsumugu's
kotobase.net graph and projecting via `aozora.appview.manga`) is built —
[gftdcojp/app-aozora#35](https://github.com/gftdcojp/app-aozora/pull/35) —
with its fetch→work-transform pure and fully tested (5 tests, no network).

### Live kotobase.net verification (2026-07-01)

`tsumugu.kotoba/kotoba-store` was smoke-tested against the real
`https://kotobase.net` edge with a self-minted CACAO identity. Found and
fixed two real protocol bugs along the way (both now merged/PR'd upstream):

- **Read vs. write need opposite scope params.** `datomic.transact` derives
  + verifies the tenant graph from `db_name` + the CACAO's own DID (a
  client-supplied `graph` is rejected); `datomic.q`/`pull`/`entid` need the
  precomputed `graph` CID directly — `db_name` alone silently matches
  nothing there (200 OK, empty `rows_edn`, no error). Fixed in
  `langchain.kotoba-db` — [kotoba-lang/langchain#5](https://github.com/kotoba-lang/langchain/pull/5)
  (merged into this repo's `kotoba-store`/`DatomicStore` in `640ce49`).
- **`datomic.pull` isn't implemented** on the live edge (404
  `MethodNotImplemented`). `DatomicStore/chapter` no longer uses `pull*` —
  rewritten as a `q`-only query.
- **Server-side bug (not a client issue), found while diagnosing empty
  reads**: `kotobase.net`'s `datomic.*` proxies to `kotobase.aozora.app`
  (`kotobase-cf-wasm`; the old K8s pod was retired 2026-06-24), whose
  `tx_edn` entity parser silently dropped any datom whose value contained
  literal `{`/`}` (i.e. any `pr-str`'d map/vector — every real panel/chapter
  payload) — `datomic.transact` returned `200 "ok"` with `datom_count: 0`,
  no error. Fixed in [gftdcojp/net-kotobase#135](https://github.com/gftdcojp/net-kotobase/pull/135)
  (open, not yet merged/deployed).

**Full live write+read round-trip is blocked on that last PR being merged
and deployed** (`wrangler deploy` to `kotobase-cf-wasm-staging` /
`kotobase.aozora.app`) — writes with real (brace-containing) payloads
currently still silently no-op on the live edge until then. Re-run
`clojure -M:dev -e` against `tsumugu.kotoba/kotoba-store` once deployed to
confirm end-to-end.

**Not yet implemented** (tracked follow-ups): the actual
`kami-mangaka-page-clj` page-layout wiring (panels compose prompts today;
page assembly is next); image-gen `render!` invocation (composition is
pure/offline today by design — the coscientist tournament never touches the
network); wiring the relay into a scheduled/cron trigger once the live
write path above is confirmed end-to-end.
