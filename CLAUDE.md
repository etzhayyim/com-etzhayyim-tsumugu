# com-etzhayyim-tsumugu

Spirit in Physics (Ghost Hacker universe) manga publishing actor. See
`README.md` for the core contract and full-repo `../../../CLAUDE.md`
"Actors" section for the pattern this follows (containment + independent
governor + append-only ledger). Decision record:
`../../../90-docs/adr/2607011500-ghosthacker-sip-atproto-actor.md`.

## Invariant

mangallm (the containment node) never commits a panel the coscientist
tournament produced no surviving candidate for. The PolicyGovernor never
lets a low-confidence or high-stakes panel auto-commit without a human.
Only `:commit` writes the Store; every commit/hold is an append-only
ledger fact.

## Conventions

- `.cljc` for anything portable (coscientist/store/phase/mangallm/policy/
  operation) — `.clj` only for JVM-only I/O (cacao, kotoba, content).
- `$SIP_IP_ROOT` (same env var as `kami-app-sip-clj`'s `sip.lore`/
  `sip.storyboard`/`sip.render`) points at the content repo
  (`org-spirit-in-physics-comics`) — one checkout serves both deployables.
- `tsumugu.render`/`tsumugu.cacao`/`tsumugu.kotoba` are faithful ports of
  `sip.render`/`itonami.cacao`/`itonami.kotoba` — kept in sync by hand
  (documented in each namespace's docstring), not shared as a runtime
  dependency, so tsumugu's own dependency footprint stays self-contained
  (containment applies to deps, not just the intelligence node).
- `clojure -M:lint` (clj-kondo, errors fail) / `clojure -M:dev:test`.
