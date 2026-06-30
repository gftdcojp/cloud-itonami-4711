# Governance

`cloud-itonami-4711` is an OSS open-business blueprint for community retail.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- sales with invalid barcodes or unbalanced receipts can never commit.
- the Retail Governor remains independent of the advisor.
- hard policy violations (void-suppression, force-reorder) cannot be overridden by human approval.
- every sell, void, reorder and reconcile path is auditable.
- customer and payment data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:
- bypassing sale or reconciliation policy checks
- mishandling customer or payment data
- misrepresenting certification status
- failing to respond to security incidents
