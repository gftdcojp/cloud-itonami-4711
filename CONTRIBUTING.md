# Contributing

`cloud-itonami-4711` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/retail`. This repo holds the
business blueprint and operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real customer, payment or supplier data.
- Keep sales, voids and reorders behind the Retail Governor.
- Treat retail workflows as high-risk: add tests for barcode, receipt
  balance, inventory, disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
