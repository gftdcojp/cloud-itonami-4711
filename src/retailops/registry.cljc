(ns retailops.registry
  "Pure-function sale-posting + reorder-commitment record construction
  -- an append-only retail book-of-record draft.

  Unlike every prior actor in this fleet, this is the FIRST vertical
  to wrap a REAL, pre-existing bespoke domain capability library
  (`kotoba-lang/retail`, this blueprint's own README-named
  implementation for 'SKU, EAN-13, POS, inventory') rather than
  building self-contained domain logic from scratch. `kotoba.retail/
  ean13-valid?`, `kotoba.retail/needs-reorder?`, `kotoba.retail/
  line-item` and `kotoba.retail/receipt` are called directly, not
  reimplemented -- the actor layer adds the governed proposal/approval
  loop on top, it does not duplicate the domain library's own
  validated logic.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a sale-posting or reorder-commitment
  record -- every shop/jurisdiction assigns its own reference format.
  This namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `retailops.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real POS/inventory system. It builds the RECORD a shop
  would keep, not the act of posting a sale or committing a reorder
  itself (that is `retailops.operation`'s `:sale/post`/`:reorder/
  commit`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]
            [kotoba.retail :as retail]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the shop's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn ean13-valid?
  "Delegates to `kotoba.retail/ean13-valid?` -- the actor layer never
  reimplements the GS1 mod-10 checksum algorithm, it calls the
  capability library's own validated function."
  [ean13]
  (retail/ean13-valid? ean13))

(defn compute-sale-total
  "The ground-truth sale total owed for `order`'s own `:quantity` and
  `:unit-price`, via `kotoba.retail/line-item`'s own net calculation --
  a single flat quantity x unit-price line, not a full tax/discount
  invoice engine."
  [{:keys [sku-id unit-price quantity]}]
  (:li/net (retail/line-item sku-id unit-price quantity)))

(defn sale-total-matches-claim?
  "Does `order`'s own `:claimed-total` equal the independently
  recomputed `compute-sale-total`? A pure ground-truth check against
  the order's own permanent fields -- the SAME discipline
  `leathergoods.registry`'s/`specialtyrepair.registry`'s own
  `parts-cost-matches-claim?` establishes (verify a claimed monetary
  total against the entity's own recorded quantity x unit fields),
  reapplied to a retail sale line rather than a repair-parts line --
  not claimed as new."
  [{:keys [claimed-total] :as order}]
  (== (double claimed-total) (compute-sale-total order)))

(defn price-within-band?
  "Does `order`'s own `:unit-price` fall within its own recorded
  `[:price-band-min :price-band-max]`? A pure ground-truth check
  against the order's own permanent fields."
  [{:keys [unit-price price-band-min price-band-max]}]
  (and (>= unit-price price-band-min) (<= unit-price price-band-max)))

(defn handoff-window-overlaps-storage-zone?
  "Positive-sense convenience predicate: does the declared handoff's
  cold-chain-temp-min-c/max-c window OVERLAP `zone`'s own
  storage-temp-min-c/max-c band at all? Mirrors cloud-itonami-
  jsic-4721's own `coldchain.facts/handoff-compatible-with-commodity-
  class?` overlap reasoning (a storage zone describes a whole
  equipment's operating band, not one delivery's declared safety
  margin, so a strict subset check in either direction would reject
  nearly every real assignment) -- an independent implementation, no
  shared code."
  [handoff-min-c handoff-max-c zone]
  (boolean
   (and (some? zone)
        (some? handoff-min-c)
        (some? handoff-max-c)
        (<= handoff-min-c handoff-max-c)
        (<= handoff-min-c (:storage-temp-max-c zone))
        (<= (:storage-temp-min-c zone) handoff-max-c))))

(defn needs-reorder?
  "Delegates to `kotoba.retail/needs-reorder?` via a `kotoba.retail/
  inventory` record built from `order`'s own `:current-stock` and
  `:reorder-at` fields -- the actor layer never reimplements the
  reorder-threshold comparison, it calls the capability library's own
  function."
  [{:keys [sku-id current-stock reorder-at]}]
  (retail/needs-reorder? (retail/inventory sku-id "default" current-stock :reorder-at reorder-at)))

(defn register-sale-completion
  "Validate + construct the SALE-POSTING registration DRAFT -- the
  shop's own legal act of posting a real sale. Pure function -- does
  not touch any real POS system; it builds the RECORD a shop would
  keep. `retailops.governor` independently re-verifies the order's own
  EAN-13/price-band/total arithmetic, and blocks a double-post of the
  same order, before this is ever allowed to commit."
  [order-id jurisdiction sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "sale-completion: order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "sale-completion: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "sale-completion: sequence must be >= 0" {})))
  (let [sale-number (str (str/upper-case jurisdiction) "-SAL-" (zero-pad sequence 6))
        record {"record_id" sale-number
                "kind" "sale-posting-draft"
                "order_id" order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "sale_number" sale-number
     "certificate" (unsigned-certificate "SalePosting" sale-number sale-number)}))

(defn register-reorder-commitment
  "Validate + construct the REORDER-COMMITMENT registration DRAFT --
  the shop's own legal act of committing a real reorder to a supplier.
  Pure function -- does not touch any real supplier-ordering system;
  it builds the RECORD a shop would keep. `retailops.governor`
  independently re-verifies the order's own reorder-threshold ground
  truth, and blocks a double-commit of the same order, before this is
  ever allowed to commit."
  [order-id jurisdiction sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "reorder-commitment: order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "reorder-commitment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "reorder-commitment: sequence must be >= 0" {})))
  (let [reorder-number (str (str/upper-case jurisdiction) "-ROR-" (zero-pad sequence 6))
        record {"record_id" reorder-number
                "kind" "reorder-commitment-draft"
                "order_id" order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "reorder_number" reorder-number
     "certificate" (unsigned-certificate "ReorderCommitment" reorder-number reorder-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
