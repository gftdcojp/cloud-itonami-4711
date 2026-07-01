# cloud-itonami-4711

Open Business Blueprint for **ISIC Rev.5 4711**: retail sale in non-specialized
stores (community retail — groceries, daily goods, local marketplace).

This repository designs a forkable OSS business for community retail: SKU and
inventory management, barcode validation, point-of-sale, and replenishment —
run by a qualified operator so a local shop keeps its own sales and stock
data instead of renting a closed POS SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a store robot performs shelfing, picking, restocking and point-of-sale handling under an actor that proposes
actions and an independent **Retail Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Core Contract

```text
intake + identity + SKU/parcel records
        |
        v
Retail Advisor -> Retail Governor -> sell, reorder, or human approval
        |
        v
POS receipt + inventory delta + reorder batch + audit ledger
```

No automated advice can post a sale with an invalid EAN-13, suppress a
reconciled receipt, or trigger a reorder without governor approval and audit
evidence.

A live sample of the operator console is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of the kotoba-lang capability UI.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4711`). Implemented by:

- [`kotoba-lang/retail`](https://github.com/kotoba-lang/retail) — SKU, EAN-13, POS, inventory

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
