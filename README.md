# cloud-itonami-isic-4711

Open Business Blueprint for **ISIC Rev.5 4711**: Community Retail
Operations -- SKU and inventory management, barcode validation,
point-of-sale, and replenishment for a local shop.

This repository publishes a community-retail actor -- order intake,
per-jurisdiction consumer-protection/unit-pricing regulatory
assessment, sale posting and reorder commitment -- as an OSS business
that any qualified operator can fork, deploy, run, improve and sell,
so an independent grocer, cooperative or small-chain operator never
surrenders sales and stock data to a closed POS SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet (86 prior actors) -- here it is
**RetailOps-LLM ⊣ Retail Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:retail-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

**This is the FIRST vertical in this fleet built on top of a real,
pre-existing bespoke domain capability library**
([`kotoba-lang/retail`](https://github.com/kotoba-lang/retail) --
SKU/EAN-13/POS/inventory pure-data contracts) rather than
self-contained domain logic: `retailops.registry` calls `kotoba.
retail/ean13-valid?` and `kotoba.retail/needs-reorder?` directly, it
does not reimplement the GS1 checksum algorithm or the reorder-
threshold comparison. This blueprint's own `docs/business-model.md`
already published a detailed `:retail-governor` Decision Rule before
this actor existed; `retailops.governor` implements that published
design faithfully.

> **Why an actor layer at all?** An LLM is great at drafting a sale
> summary, normalizing records, and checking whether a claimed sale
> total actually equals the order's own recorded quantity times unit-
> price -- but it has **no notion of which jurisdiction's consumer-
> protection/unit-pricing law is official, no license to post a real
> sale or commit a real reorder, and no way to know on its own whether
> a barcode is a genuine GS1 EAN-13 or whether a unit price actually
> falls within the SKU's own declared price band**. Letting it post a
> sale or commit a reorder directly invites fabricated regulatory
> citations, a mismatched sale total being charged to a customer, an
> unscannable/counterfeit barcode being sold, and a unit price outside
> its own declared band reaching a shelf in violation of real unit-
> pricing law -- exposing the shop to real consumer-protection
> liability -- and liability, for whoever runs it. This project seals
> the RetailOps-LLM into a single node and wraps it with an
> independent **Retail Governor**, a human **approval workflow**, and
> an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers order intake through consumer-protection/unit-
pricing regulatory assessment, sale posting and reorder commitment. It
does **not**, by itself, hold any license required to operate a retail
shop in a given jurisdiction, and it does not claim to. It also does
not perform the actual point-of-sale scanning/checkout hardware
interaction itself, or judge merchandising quality --
`retailops.registry/sale-total-matches-claim?` is a pure ground-truth
recompute against the order's own recorded fields, not a pricing-
strategy judgment. Whoever deploys and operates a live instance (a
qualified shop operator/authorized clerk) supplies any jurisdiction-
specific license, the real POS hardware integration and the real
supplier-ordering-system integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch.

### Actuation

**Posting a real sale and committing a real reorder are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`retailops.governor`'s `:actuation/post-sale`/
`:actuation/commit-reorder` high-stakes gate and `retailops.phase`'s
phase table, which never puts either op in any phase's `:auto` set) --
see `retailops.phase`'s docstring and `test/retailops/phase_test.clj`'s
`sale-post-never-auto-at-any-phase`/`reorder-commit-never-auto-at-any-
phase`. The actor may draft, check and recommend; a human shop
operator is always the one who actually posts a sale or commits a
reorder. Grounded directly in this blueprint's own `docs/business-
model.md` text ("`cloud-itonami-4711` never lets a SKU move -- sale,
reorder, or shelf restock -- without a decision from `:retail-
governor`") -- a genuine DUAL-actuation shape (two distinct real-world
acts), matching this fleet's own established convention.

## The core contract

```
order intake + jurisdiction facts (retailops.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────┐
   │ RetailOps-LLM         │ ─────────────▶ │ Retail Governor              │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-       │
   └───────────────────────┘                 │ incomplete · sale-total-     │
          │                 commit ◀┼ mismatch (ground-truth) ·        │
          │                         │ ean13-invalid (NEW, capability-       │
    record + ledger        escalate ┼ lib reuse) · price-band-violation      │
          │              (ALWAYS for│ (FLAGSHIP NEW) · reorder-threshold-       │
          │       :actuation/post-  │ mismatch (ground-truth) ·                 │
          │       sale/:actuation/  │ already-sold · already-reordered          │
          │       commit-reorder)   │                                            │
          ▼                          └───────────────────────┘
      human approval
```

**The RetailOps-LLM never posts a sale or commits a reorder the
Retail Governor would reject, and never does so without a human sign-
off.** Hard violations (fabricated regulatory requirements;
unsupported evidence; a sale-total mismatch; an invalid EAN-13; a
price outside its own declared band; a reorder proposed on stock that
doesn't actually need one; a double post/commit) force **hold** and
*cannot* be approved past; a clean post/commit proposal still always
routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean sale + one clean reorder lifecycle, plus six HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a store robot performs
shelfing, picking, restocking and point-of-sale handling, under the
actor, gated by the independent **Retail Governor**. The governor
never dispatches hardware itself: a robotic restocking cart or picker
placing stock on a shelf must clear the same pricing sign-off a human
operator would need.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Retail Governor, sale-posting/reorder-commitment draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record. A companion playable prototype,
"ITONAMI: Retail Shift", lives in `gftdcojp/network-isekai`
(`:itonami.blueprint/game`, ADR-2607031000).

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4711`). Unlike most verticals in this fleet, this one IS backed by a
bespoke domain capability lib: [`kotoba-lang/retail`](https://github.com/kotoba-lang/retail)
(SKU, EAN-13, POS, inventory pure-data contracts) -- `retailops.*`
calls its functions directly rather than reimplementing them, on top
of the generic robotics/identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/retailops/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + sale-posting AND reorder-commitment history (dual history, matching every sibling actor's own shape). The double-actuation guard checks dedicated `:sale-posted?`/`:reorder-committed?` booleans rather than a `:status` value |
| `src/retailops/registry.cljc` | Sale-posting/reorder-commitment draft records, wrapping `kotoba.retail`'s own `ean13-valid?`/`needs-reorder?`/`line-item` functions rather than reimplementing them; `sale-total-matches-claim?` is the ground-truth-recompute discipline every sibling actor's parts-cost check establishes, reapplied to a retail sale line |
| `src/retailops/facts.cljc` | Per-jurisdiction consumer-protection AND unit-pricing/price-marking catalog with an official spec-basis citation per entry, honest coverage reporting -- ALL FOUR seeded jurisdictions have a unit-pricing sub-citation here |
| `src/retailops/retailopsllm.cljc` | **RetailOps-LLM** -- `mock-advisor` ‖ `llm-advisor`; intake/jurisdiction-assessment/sale-posting/reorder-commitment proposals |
| `src/retailops/governor.cljc` | **Retail Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · sale-total-mismatch · ean13-invalid, capability-lib reuse, the 71st unconditional-evaluation-discipline grounding · price-band-violation, FLAGSHIP NEW, the 72nd grounding · reorder-threshold-mismatch) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/retailops/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (sale posting/reorder commitment always human; order intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/retailops/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/retailops/sim.cljc` | demo driver |
| `test/retailops/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers order intake through consumer-protection/unit-
pricing regulatory assessment, sale posting and reorder commitment --
the core governed lifecycle this blueprint's own `docs/business-
model.md` names in its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Order intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:order/intake`/`:jurisdiction/assess`) | Real POS hardware/supplier-ordering-system integration (see `retailops.facts`'s docstring) |
| Sale posting, HARD-gated on full evidence, a matching sale total, a valid EAN-13 and a price within the SKU's own declared band, plus a double-post guard (`:actuation/post-sale`) | Daily reconciliation/cash-up, void-without-reason blocking, cash-discrepancy escalation -- all named in the blueprint's own Decision Rule, but a follow-up slice, not in this R0 |
| Reorder commitment, HARD-gated on full evidence and a genuine reorder-threshold need, plus a double-commit guard (`:actuation/commit-reorder`) | |
| Immutable audit ledger for every intake/assessment/sale/reorder decision | |

Extending coverage is additive: add the next gate (e.g. a
void-without-reason check or a cash-discrepancy-escalation check,
both already named in the published Decision Rule) as its own governed
op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records
before any real-world act" pattern this repo's flagship ops already
establish.

## Jurisdiction coverage (honest)

`retailops.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `retailops.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `retailops.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger. Note that the unit-pricing sub-citation is
FULL coverage rather than a gap: ALL FOUR seeded jurisdictions (JPN,
USA, GBR, DEU) actually have a real unit-pricing/price-marking
enforcement regime, reported honestly.

## Maturity

`:implemented` -- `RetailOps-LLM` + `Retail Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, following the SAME governed-actor
architecture as the 86 other prior actors across this fleet, with its
own distinct, independently-named governor and its own novel
integration with a real, pre-existing bespoke capability library. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
