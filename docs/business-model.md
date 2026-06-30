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
