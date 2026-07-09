# ADR-0001: RetailOps-LLM ⊣ Retail Governor architecture

## Status

Accepted. `cloud-itonami-isic-4711` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-4711` publishes an OSS business blueprint for
community retail operations (SKU/inventory management, barcode
validation, point-of-sale, replenishment). Like every prior actor in
this fleet, the blueprint alone is not an implementation: this ADR
records the governed-actor architecture that promotes it to real,
tested code, following the same langgraph StateGraph + independent
Governor + Phase 0→3 rollout pattern established by `cloud-itonami-
isic-6511` (life insurance) and applied across 86 prior siblings, most
recently `cloud-itonami-isic-9511` (community ICT equipment repair).

Unlike every prior sibling, this blueprint's own `docs/business-
model.md` already published a fully detailed `:retail-governor`
Decision Rule -- approve/reject conditions, a required-technologies
table explaining what each technology is load-bearing for, and an
explicit tie to a companion playable prototype (`network-isekai`'s
"ITONAMI: Retail Shift") -- BEFORE this actor's code existed. This
build implements that published design faithfully rather than
inventing an architecture from a generic template.

This is also the FIRST vertical in this fleet built on top of a real,
pre-existing bespoke domain capability library
([`kotoba-lang/retail`](https://github.com/kotoba-lang/retail) --
SKU/EAN-13/POS/inventory pure-data contracts, named in this
blueprint's own README `Capability layer` section) rather than
self-contained domain logic. Every prior sibling built its own domain
logic from scratch (parts-cost recomputation, safety-test tracking,
etc.); this one wraps an existing, independently-tested library
instead of duplicating its logic.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:retail-governor`, is grep-verified UNIQUE fleet-wide -- no naming-
collision precedent question, a fresh independent build.

## Decision

### Decision 1: implement the ALREADY-PUBLISHED Decision Rule faithfully

`retailops.governor` is not a fresh design -- it is a faithful
implementation of the Decision Rule `docs/business-model.md` already
published: approve only when EAN-13 checksum passes, unit price is
within the catalog's price band, and the action is a legitimate step
in the declared workflow; reject/escalate on invalid EAN-13, silent
void, unpriced restock, or unescalated cash discrepancy. This build
implements the EAN-13, price-band, and reorder-threshold portions of
that rule; reconciliation/void/cash-discrepancy handling (also named
in the rule) is a follow-up slice -- see Decision 9.

### Decision 2: wrap `kotoba-lang/retail`, don't reimplement it

`retailops.registry/ean13-valid?` and `retailops.registry/needs-
reorder?` delegate directly to `kotoba.retail/ean13-valid?` and
`kotoba.retail/needs-reorder?` (via a `kotoba.retail/inventory`
record). `retailops.registry/compute-sale-total` uses `kotoba.retail/
line-item`'s own net calculation. The actor layer adds the governed
proposal/approval loop on top of this existing, independently-tested
domain library; it does not duplicate the GS1 mod-10 checksum
algorithm or the reorder-threshold comparison. This is a genuinely
new architectural pattern for this fleet -- documented explicitly so
future builds know to check for an existing capability lib before
writing domain logic from scratch (see this repo's own README
`Capability layer` naming `kotoba-lang/retail`).

### Decision 3: dual-actuation shape, on an `order` entity distinguished by `:kind`

Unlike the repair-shop-cluster's `ticket` entity (which always has the
SAME dual-actuation shape per ticket -- repair then return), this
vertical's primary entity is an `order`, distinguished by its own
`:kind` (`:sale` | `:reorder`). A given order's `:kind` means only ONE
of `:sale-posted?`/`:reorder-committed?` is ever meaningfully
exercised for that order -- the same "both booleans always present,
only one relevant" pattern every ticket in this fleet already uses for
conditional fields. `high-stakes` is `#{:actuation/post-sale
:actuation/commit-reorder}`, matching this blueprint's own "sale,
reorder, or shelf restock" framing.

### Decision 4: `sale-total-matches-claim?` and `reorder-threshold-mismatch?` -- the SAME ground-truth-recompute discipline, reapplied

`retailops.registry/sale-total-matches-claim?` (order's own claimed
total vs. quantity x unit-price) and `retailops.governor/reorder-
threshold-mismatch-violations` (order's own recorded stock vs. its own
reorder threshold, via `kotoba.retail/needs-reorder?`) both apply the
SAME ground-truth-recompute DISCIPLINE `leathergoods.registry`'s/
`specialtyrepair.registry`'s own `parts-cost-matches-claim?`
establishes -- verify a claimed fact against the entity's own
recorded fields, independent of proposal inspection. No literal code
is shared (different domain, different capability library), but the
discipline is the same, and is documented as such rather than claimed
as a novel invention.

### Decision 5: `ean13-invalid?` -- the 71st unconditional-evaluation grounding, a genuinely new category (capability-library-validated-fact reuse)

Before writing this check, every prior sibling's governor namespace
across the entire fleet was grepped for any check function named
`ean13`, `barcode` or `gs1` -- zero hits, confirming this is a
genuinely new concept. Unlike every prior check in this discipline
(which reuses either a SIBLING ACTOR's own check or invents a fresh
one), this check reuses a CAPABILITY LIBRARY's own validated function
(`kotoba.retail/ean13-valid?`) -- a genuinely new sub-category. The
71st distinct application of the unconditional-evaluation-screening
discipline overall (most recently `ictrepair.governor/media-
sanitization-unconfirmed-violations` at 70th). Evaluated
UNCONDITIONALLY on every `:sale/post` (every sale needs a valid
barcode).

### Decision 6: `price-band-violation?` -- the 72nd unconditional-evaluation grounding, the FLAGSHIP genuinely new check

Grep-verified absent fleet-wide (zero hits for `price-band`, `unit-
pricing`, `price-marking`). Grounded in real unit-pricing/price-
marking law: the US NIST Handbook 130 (Uniform Regulation for the
Method of Sale of Commodities, adopted by most states), the UK's Price
Marking Order 2004, Germany's Preisangabenverordnung (PAngV,
implementing EU Directive 98/6/EC), and Japan's own 計量法 (Measurement
Act) unit-price provisions. Unlike some prior repair-shop-cluster
siblings' own honest single-jurisdiction gap, ALL FOUR seeded
jurisdictions actually have a real regime here, reported honestly
(matching `leathergoods`/9523's own and `ictrepair`/9511's own
full-coverage sub-citations). Evaluated UNCONDITIONALLY on every
`:sale/post` (every sale needs a price within its own declared band).

### Decision 7: dedicated double-actuation-guard booleans

`:sale-posted?`/`:reorder-committed?` are dedicated booleans on the
`order` record, never a single `:status` value -- the same discipline
every prior governor's guards establish, informed by `cloud-itonami-
isic-6492`'s real status-lifecycle bug (ADR-2607071320).

### Decision 8: Store protocol, MemStore + DatomicStore parity

`retailops.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore` (`langchain.
db`-backed), proven to satisfy the same contract in
`test/retailops/store_contract_test.clj` -- the same seam every
sibling actor uses.

### Decision 9: `blueprint.edn` field-sync fix, and scoped-down R0

Unlike the last several builds, this repo's `blueprint.edn` DID need a
field-sync fix: `:required-technologies` was missing `:robotics`
(present in the `kotoba-lang/industry` registry's own entry for
`"4711"` but absent from the blueprint's own list) -- fixed as part of
this promotion. Separately, this R0 build deliberately scopes DOWN
from the full Decision Rule already published: reconciliation/void/
cash-discrepancy handling (also named in that rule) is left as a
follow-up slice, not built in this commit, to keep the initial governed
slice to a size consistent with every other actor in this fleet.

### Decision 10: mock + LLM advisor pair

`retailops.retailopsllm` provides `mock-advisor` (deterministic,
default everywhere -- the actor graph and governor contract run
offline) and `llm-advisor` (backed by `langchain.model/ChatModel`,
with a defensive EDN-proposal parser so a malformed LLM response
degrades to a safe low-confidence noop rather than ever auto-posting a
sale or auto-committing a reorder).

## Alternatives considered

- **Reimplementing EAN-13 checksum and reorder-threshold logic
  in-repo.** Rejected: `kotoba-lang/retail` already provides
  independently-tested, pure-data functions for exactly this; wrapping
  it is more honest and less error-prone than duplicating the GS1
  mod-10 algorithm.
- **Building the FULL Decision Rule in one commit** (including
  reconciliation, void-without-reason, and cash-discrepancy
  escalation). Rejected in favor of a scoped R0 slice, consistent with
  every prior actor's own "extending coverage is additive" convention
  -- the sale/reorder actuation core is the load-bearing slice;
  reconciliation is a natural, separately-testable follow-up.
- **A single `ticket`-style entity name** (matching the repair-shop
  cluster). Rejected: `order` (distinguished by `:kind`) is the
  domain-honest name for a POS sale line or a supplier reorder, and
  `kotoba.retail`'s own vocabulary (SKU, line-item, receipt) already
  establishes this terminology.

## Consequences

- 87th actor in this fleet (86 implemented before this build).
- FIRST vertical in this fleet to integrate a real, pre-existing
  bespoke domain capability library rather than self-contained logic
  -- a new architectural pattern worth checking for on future builds.
- Establishes two genuinely NEW unconditional-evaluation-discipline
  checks: `ean13-invalid?` (capability-library-validated-fact reuse,
  71st) and `price-band-violation?` (FLAGSHIP, jurisdiction-grounded,
  72nd).
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/retailops/store_contract_test.clj`.
- 44 tests / 186 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean sale lifecycle, one clean
  reorder lifecycle, and six HARD-hold scenarios end-to-end.
- `blueprint.edn` required a field-sync fix (`:robotics` was missing
  from `:required-technologies`) in addition to the `:maturity` flip.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-9523/docs/adr/0001-architecture.md`,
  `cloud-itonami-isic-9511/docs/adr/0001-architecture.md` (most recent
  prior siblings, template for this ADR's structure)
- `kotoba-lang/retail` (the capability library this build wraps)
- NIST Handbook 130, Uniform Regulation for the Method of Sale of
  Commodities (US)
- Price Marking Order 2004 (UK)
- Preisangabenverordnung (PAngV) (Germany)
- 計量法 (Measurement Act) (Japan)
