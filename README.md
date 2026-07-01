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

21 tests, 58 assertions, green (`clojure -M:dev:test`). Identity, store
(Mem ≡ Datomic parity), content-read (verified against all 62 real chapters),
coscientist tournament, and the full StateGraph (approval interrupt/resume,
phase-gated auto-commit, hold-on-no-survivors) are exercised end-to-end
against real Spirit in Physics storyboard data.

**Not yet implemented** (tracked follow-ups): the `aozora-relay` downstream
adapter (app-aozora polling/subscribing tsumugu's kotobase.net graph and
projecting via `aozora.appview.manga`); the actual `kami-mangaka-page-clj`
page-layout wiring (panels compose prompts today; page assembly is next);
image-gen `render!` invocation (composition is pure/offline today by design —
the coscientist tournament never touches the network).
