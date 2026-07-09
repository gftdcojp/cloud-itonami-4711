# Business Model: Community Retail Operations

## Classification
- Repository: `cloud-itonami-4711`
- ISIC Rev.5: `4711` — retail sale in non-specialized stores
- Social impact: local economy, food security, transparent pricing

## Customer
- independent grocers and corner shops
- cooperatives and farmers'-market operators
- small-chain operators leaving closed POS SaaS
- community buying groups

## Offer
- SKU and barcode (EAN-13) management
- point-of-sale receipts with tax breakdown
- inventory with reorder thresholds
- daily reconciliation and cash-up
- supplier reorder workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per store
- support retainer with SLA
- integration with payment and supplier systems

## Trust Controls
- sales with invalid EAN-13 cannot be committed
- reconciled receipts cannot be silently voided
- reorders require governor approval
- cash discrepancies are logged and escalated
- customer data stays outside Git

## The `retail-governor` Decision Rule

`cloud-itonami-4711` never lets a SKU move — sale, reorder, or shelf restock —
without a decision from `:retail-governor`. The rule is deliberately narrow
(one governor, one domain, no generic "approve anything" policy):

**Approves** an action only when *all* of the following hold:
- the SKU's barcode passes EAN-13 checksum validation (`:retail` catalog data)
- the requesting operator/agent is identity-verified as authorized to sign off
  on pricing or reorder for that store (`:identity`)
- a `:dmn` decision table clears the action against the current rule set —
  e.g. reorder quantity vs. threshold + supplier lead time, or unit price vs.
  the catalog's price band — rather than a hardcoded if/else a developer would
  have to redeploy to change
- the action is a legitimate step in the `:bpmn` reconciliation/reorder
  process (no restock outside the declared workflow, no cash-up close before
  the sign-off step)

**Rejects / escalates** when:
- a sale line carries an invalid or unrecognized EAN-13
- a reconciled receipt is voided without a human-reviewed reason
- a shelf is restocked (or a robotic picker places stock) *before* the
  pricing sign-off — the item is left effectively "unpriced" and cannot be
  legally sold, which the governor logs as a violation rather than silently
  letting the sale happen
- a cash discrepancy appears at close without an escalation record

**Why this rule, for this business specifically**: the blueprint's
`:social-impact` tags are `local-economy`, `food-security`, and
`transparency`, and the rule is built to serve exactly those three, not a
generic "compliance" story —
- *local-economy*: the sign-off is cheap and fast (one stockroom pull, one
  DMN check) so an independent grocer or co-op running this on modest
  hardware isn't priced out the way closed POS SaaS would price them out;
  the governor protects margin (no unpriced/unsellable stock, no silent
  voids) without adding staff.
- *food-security*: because the domain is a *retail* operation carrying
  perishable/essential goods, the governor is tuned to keep restock moving
  (approve promptly when the rule set clears) rather than blocking reorders
  by default — a shelf that never gets its sign-off is a shelf of food that
  never reaches a customer.
- *transparency*: every governor decision — approve, reject, or escalate —
  is written to the `:audit-ledger` immutable log, so a customer, supplier,
  or regulator can see exactly which SKUs were reordered, at what price, and
  who signed off, matching the "customer data stays outside Git" /
  "cash discrepancies are logged" trust controls above.

Every one of `:retail-governor`'s gates maps 1:1 onto the approval loop
`network-isekai`'s playable prototype "ITONAMI: Retail Shift" simulates: pull
stock at the "stockroom" for the pricing sign-off before a "shelf" job counts
as cleared, or the shift is closed with an unpriced shelf. See
`docs/operator-guide.md` for the walkthrough.

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` is
`[:retail :identity :forms :dmn :bpmn :audit-ledger]`. Each one exists in
this business for a specific reason, not as boilerplate infrastructure:

- **`:retail`** — the domain module itself: SKU/EAN-13 catalog, POS receipt
  generation with tax breakdown, inventory ledger with reorder thresholds,
  and the supplier-reorder record type that `:retail-governor` gates.
- **`:identity`** — role-based access so only an authorized operator (store
  owner, certified clerk) can execute a pricing sign-off, void a receipt, or
  close daily reconciliation; also the boundary that keeps customer/PII data
  out of the Git-tracked catalog and audit history.
- **`:forms`** — the intake surface: store/SKU/supplier/operator
  registration, opening-inventory import, reorder requests, and the daily
  cash-up form that starts the reconciliation gate.
- **`:dmn`** — the decision tables `:retail-governor` evaluates against:
  EAN-13 validation rules, reorder-threshold-vs-lead-time logic, and
  price-band checks, kept declarative so a store operator can tune the
  business rules (e.g. a new supplier's lead time) without a code change.
- **`:bpmn`** — the process definition for the intake → propose → approve →
  execute → audit loop: the reorder-approval process and the daily
  reconciliation/cash-up sequence, so the governor gate is enforced at a
  specific workflow step rather than scattered through application code.
- **`:audit-ledger`** — the immutable log every governor decision, void, and
  reorder writes to; this is what makes the `transparency` social-impact tag
  a concrete, exportable artifact rather than a claim.

`:itonami.blueprint/optional-technologies [:optimization]` is not part of the
trust boundary — it is available for reorder-quantity/shelf-placement
optimization once the core governor loop above is running, and its absence
never blocks a sale or a sign-off.

`:itonami.blueprint/robotics true` means the same gate applies to physical
actuation: a robotic restocking cart or picker placing stock on a shelf must
have cleared the same `:retail-governor` pricing sign-off a human operator
would need — the governor does not distinguish between a human hand and a
robot arm putting a SKU on the shelf.

## Implementation notes (`:implemented`)

The Decision Rule above was published before this actor existed; the actual
`retailops.governor` implements it faithfully, calling `kotoba-lang/retail`'s
own validated `ean13-valid?`/`needs-reorder?`/`line-item` functions rather
than reimplementing them:

- `ean13-invalid-violations` — the EAN-13-checksum check above, delegating to
  the capability library, evaluated unconditionally on every `:sale/post`.
- `price-band-violation-violations` — the price-vs-band check above, the
  FLAGSHIP genuinely new check this vertical adds, grounded in a real
  4-jurisdiction unit-pricing/price-marking catalog (`retailops.facts`): the
  US NIST Handbook 130, the UK's Price Marking Order 2004, Germany's
  Preisangabenverordnung (PAngV), and Japan's own 計量法 (Measurement Act).
- `sale-total-mismatch-violations` / `reorder-threshold-mismatch-violations`
  — independent ground-truth recomputes of the sale total and the reorder
  threshold against the order's own recorded fields.
- `already-sold-violations` / `already-reordered-violations` — the
  double-actuation guards every sibling actor in this fleet uses.

`:sale/post` and `:reorder/commit` are the two real-world actuation events
(`#{:actuation/post-sale :actuation/commit-reorder}`), matching this
blueprint's own "sale, reorder, or shelf restock" framing above — neither
ever auto-commits at any phase. Reconciliation/void/cash-discrepancy
handling (also named in the Decision Rule above) is a follow-up slice, not
in this R0 build — see README `Business-process coverage`.
