(ns retailops.governor
  "Retail Governor -- the independent compliance layer that earns the
  RetailOps-LLM the right to commit. The LLM has no notion of
  jurisdictional consumer-protection/unit-pricing law, whether a
  claimed sale total actually equals the order's own quantity times
  unit-price, whether a SKU's own barcode is a genuine GS1 EAN-13,
  whether a unit price actually falls within the SKU's own declared
  price band, whether a reorder is actually justified by the SKU's own
  recorded stock level, or when an act stops being a draft and becomes
  a real-world sale or reorder, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD.

  This is the FIRST vertical in this fleet built on TOP of a real,
  pre-existing bespoke domain capability library (`kotoba-lang/
  retail`) rather than self-contained domain logic -- `retailops.
  registry` calls `kotoba.retail/ean13-valid?` and `kotoba.retail/
  needs-reorder?` directly rather than reimplementing them. This
  blueprint's own `docs/business-model.md` already publishes a
  detailed `:retail-governor` Decision Rule (written before this
  actor existed) naming exactly the checks below; this governor
  implements that published design faithfully rather than inventing
  one from a generic template.

  `:itonami.blueprint/governor` is `:retail-governor`, grep-verified
  UNIQUE fleet-wide -- no naming-collision precedent question, a
  fresh independent build following the SAME governed-actor
  architecture (langgraph StateGraph + independent Governor + Phase
  0->3 rollout) established by `cloud-itonami-isic-6511`.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `retailops.phase`: for `:stake :actuation/post-
  sale`/`:actuation/commit-reorder` (a real sale or reorder) NO phase
  ever allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`retailops.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:sale/post`/`:reorder/
                                       commit`, has the jurisdiction
                                       actually been assessed with a
                                       full evidence checklist on file?
    3. Sale total mismatch         -- for `:sale/post`, INDEPENDENTLY
                                       recompute whether the order's
                                       own `:claimed-total` equals
                                       `quantity x unit-price`
                                       (`retailops.registry/sale-
                                       total-matches-claim?`) -- an
                                       HONEST reuse of the SAME
                                       ground-truth-recompute
                                       DISCIPLINE `leathergoods.
                                       registry`'s/`specialtyrepair.
                                       registry`'s own `parts-cost-
                                       matches-claim?` establishes
                                       (verify a claimed monetary total
                                       against the entity's own
                                       recorded quantity x unit
                                       fields), reapplied to a retail
                                       sale line rather than a repair-
                                       parts line -- not claimed as new
                                       code, though no literal code is
                                       shared (different domain,
                                       different capability lib).
    4. EAN-13 invalid              -- for `:sale/post`, INDEPENDENTLY
                                       verify the order's own SKU
                                       barcode passes the GS1 mod-10
                                       checksum via `kotoba.retail/
                                       ean13-valid?` -- the actor layer
                                       calls the CAPABILITY LIBRARY's
                                       own validated function rather
                                       than reimplementing the
                                       checksum, a genuinely new
                                       category in this discipline
                                       (reusing a capability library's
                                       own logic, not a sibling
                                       actor's check). The 71st
                                       distinct application of the
                                       unconditional-evaluation
                                       discipline overall (most
                                       recently `ictrepair.governor/
                                       media-sanitization-unconfirmed-
                                       violations` at 70th). Evaluated
                                       UNCONDITIONALLY (every sale
                                       needs a valid barcode).
    5. Price band violation        -- for `:sale/post`, INDEPENDENTLY
                                       verify the order's own
                                       `:unit-price` falls within its
                                       own recorded `[:price-band-min
                                       :price-band-max]`
                                       (`retailops.registry/price-
                                       within-band?`) -- the FLAGSHIP
                                       genuinely new check this
                                       vertical adds (grep-verified
                                       absent fleet-wide -- zero hits
                                       for 'price-band'/'unit-
                                       pricing'/'price-marking' as a
                                       governor check function name),
                                       the 72nd distinct application of
                                       the discipline overall. Grounded
                                       in real unit-pricing/price-
                                       marking law: the US NIST
                                       Handbook 130 (adopted by most
                                       states), the UK's Price Marking
                                       Order 2004, Germany's
                                       Preisangabenverordnung (PAngV,
                                       implementing EU Directive
                                       98/6/EC), Japan's own 計量法
                                       (Measurement Act) unit-price
                                       provisions, and India/Saudi
                                       Arabia/UAE/Mexico's own seeded
                                       regimes (`retailops.facts`) --
                                       ALL EIGHT seeded jurisdictions
                                       actually have a real regime here,
                                       reported honestly (matching
                                       `leathergoods`/9523's own and
                                       `ictrepair`/9511's own full-
                                       coverage sub-citations rather
                                       than forcing an artificial gap).
                                       Evaluated UNCONDITIONALLY (every
                                       sale needs a price within its
                                       own declared band).
    6. Reorder threshold mismatch  -- for `:reorder/commit`,
                                       INDEPENDENTLY verify via
                                       `kotoba.retail/needs-reorder?`
                                       (through `retailops.registry/
                                       needs-reorder?`) that the SKU's
                                       own recorded `:current-stock`
                                       is actually at or below its own
                                       `:reorder-at` threshold before
                                       allowing a reorder commit --
                                       the SAME ground-truth-recompute
                                       DISCIPLINE as check 3, reapplied
                                       to a different domain fact
                                       (stock level vs. sale total).
    7. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:sale/post`/
                                       `:reorder/commit` (REAL acts) ->
                                       escalate.

  Two more guards, double-post/double-commit prevention, are enforced
  but NOT listed as numbered HARD checks above because they need no
  upstream comparison at all -- `already-sold-violations`/`already-
  reordered-violations` refuse to post/commit the SAME order twice,
  off dedicated `:sale-posted?`/`:reorder-committed?` facts (never a
  `:status` value) -- the SAME 'check a dedicated boolean, not status'
  discipline every prior governor's guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  A further additive HARD guard, `cold-chain-handoff-violations`
  (superproject ADR-2800000500): a `:reorder/receive` (or any other)
  proposal whose `:value` carries BOTH a `:handoff` record (the wire
  shape an upstream cold-chain 3PL such as cloud-itonami-jsic-4721
  populates on ITS OWN outbound dispatch -- same field names as that
  repo's own ADR-2607177600 `:handoff`, no shared code, no shared
  store) AND a `:storage-zone-id` naming which of this store's own
  cold-storage zones (`retailops.facts/cold-storage-zones`) the
  delivery is being placed into is independently checked via
  `retailops.registry/handoff-window-overlaps-storage-zone?` --
  catching a temperature-tier mismatch (e.g. a frozen delivery placed
  into a refrigerated zone) before it reaches this actor's own store.
  Optional on both fields, same asymmetric discipline as every other
  cross-actor reference check in this fleet: a proposal missing either
  field is never held on this basis."
  (:require [retailops.facts :as facts]
            [retailops.registry :as registry]
            [retailops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Posting a real sale and committing a real reorder are the two
  real-world actuation events this actor performs -- a two-member set,
  matching every sibling's own dual-actuation shape."
  #{:actuation/post-sale :actuation/commit-reorder})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:sale/post`/`:reorder/commit`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's consumer-protection/unit-pricing
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :sale/post :reorder/commit} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:sale/post`/`:reorder/commit`, the jurisdiction's required
  SKU-registration/pricing-authorization/sale-record/unit-pricing
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:sale/post :reorder/commit} op)
    (let [o (store/order st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction o) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(SKU登録記録/価格承認記録/販売記録/単価表示記録等)が充足していない状態での提案"}]))))

(defn- sale-total-mismatch-violations
  "For `:sale/post`, INDEPENDENTLY recompute whether the order's own
  claimed total equals quantity x unit-price via `retailops.registry/
  sale-total-matches-claim?` -- needs no proposal inspection or
  stored-verdict lookup at all, the same ground-truth-recompute
  discipline every sibling actor's parts-cost check establishes."
  [{:keys [op subject]} st]
  (when (= op :sale/post)
    (let [o (store/order st subject)]
      (when-not (registry/sale-total-matches-claim? o)
        [{:rule :sale-total-mismatch
          :detail (str subject " の申告合計金額(" (:claimed-total o)
                      ")が独立再計算値(" (registry/compute-sale-total o) ")と一致しない")}]))))

(defn- ean13-invalid-violations
  "For `:sale/post`, INDEPENDENTLY verify the order's own SKU barcode
  passes the GS1 mod-10 checksum via `kotoba.retail/ean13-valid?`
  (through `retailops.registry/ean13-valid?`) -- calls the capability
  library's own validated function, does not reimplement it. Evaluated
  UNCONDITIONALLY (every sale needs a valid barcode)."
  [{:keys [op subject]} st]
  (when (= op :sale/post)
    (let [o (store/order st subject)]
      (when-not (registry/ean13-valid? (:ean13 o))
        [{:rule :ean13-invalid
          :detail (str subject " のバーコード(" (:ean13 o) ")がGS1チェックデジット検証に失敗")}]))))

(defn- price-band-violation-violations
  "For `:sale/post`, INDEPENDENTLY verify the order's own unit price
  falls within its own recorded price band via `retailops.registry/
  price-within-band?` -- the flagship genuinely new check this
  vertical adds. Evaluated UNCONDITIONALLY (every sale needs a price
  within its own declared band)."
  [{:keys [op subject]} st]
  (when (= op :sale/post)
    (let [o (store/order st subject)]
      (when-not (registry/price-within-band? o)
        [{:rule :price-band-violation
          :detail (str subject " の単価(" (:unit-price o) ")が価格帯["
                      (:price-band-min o) "," (:price-band-max o) "]の範囲外")}]))))

(defn- reorder-threshold-mismatch-violations
  "For `:reorder/commit`, INDEPENDENTLY verify via `kotoba.retail/
  needs-reorder?` (through `retailops.registry/needs-reorder?`) that
  the SKU's own recorded stock is actually at or below its own reorder
  threshold -- the SAME ground-truth-recompute discipline as
  `sale-total-mismatch-violations`, reapplied to a different domain
  fact."
  [{:keys [op subject]} st]
  (when (= op :reorder/commit)
    (let [o (store/order st subject)]
      (when-not (registry/needs-reorder? o)
        [{:rule :reorder-threshold-mismatch
          :detail (str subject " の在庫(" (:current-stock o) ")は発注点(" (:reorder-at o) ")を上回っており発注不要")}]))))

(defn- already-sold-violations
  "For `:sale/post`, refuses to post the SAME order's sale twice, off
  a dedicated `:sale-posted?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :sale/post)
    (when (store/order-already-sold? st subject)
      [{:rule :already-sold
        :detail (str subject " は既に販売計上済み")}])))

(defn- cold-chain-handoff-violations
  "HARD, additive: when a proposal's `:value` carries BOTH a `:handoff`
  record and a `:storage-zone-id`, independently verify the handoff's
  declared cold-chain-temp-min-c/max-c window overlaps that storage
  zone's own reference band (`retailops.facts/cold-storage-zones`).
  Optional on both fields -- a proposal missing either is never held
  on this basis (same asymmetric discipline as every cross-actor
  reference check in this fleet, e.g. cloud-itonami-jsic-4721's own
  `lot-physical-violations`). Evaluated unconditionally on every
  proposal, not gated to a specific op -- the same 'runs everywhere,
  no-ops without both optional fields' shape every other check that
  is not itself op-gated in this ns uses."
  [proposal]
  (let [handoff (get-in proposal [:value :handoff])
        zone-id (get-in proposal [:value :storage-zone-id])
        zone (facts/cold-storage-zone-by-id zone-id)
        handoff-min (:handoff/cold-chain-temp-min-c handoff)
        handoff-max (:handoff/cold-chain-temp-max-c handoff)]
    (when (and (map? handoff) (some? zone) (some? handoff-min) (some? handoff-max)
               (not (registry/handoff-window-overlaps-storage-zone? handoff-min handoff-max zone)))
      [{:rule :handoff-cold-chain-window-incompatible-with-storage-zone
        :detail (str "受領handoffの宣言コールドチェーン窓(" handoff-min "℃~" handoff-max
                      "℃)が割り当てられた保管ゾーン(" (pr-str zone-id) ")の運用帯と重ならない -- 温度帯不整合")}])))

(defn- already-reordered-violations
  "For `:reorder/commit`, refuses to commit the SAME order's reorder
  twice, off a dedicated `:reorder-committed?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :reorder/commit)
    (when (store/order-already-reordered? st subject)
      [{:rule :already-reordered
        :detail (str subject " は既に発注確定済み")}])))

(defn check
  "Censors a RetailOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (sale-total-mismatch-violations request st)
                           (ean13-invalid-violations request st)
                           (price-band-violation-violations request st)
                           (reorder-threshold-mismatch-violations request st)
                           (already-sold-violations request st)
                           (already-reordered-violations request st)
                           (cold-chain-handoff-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
