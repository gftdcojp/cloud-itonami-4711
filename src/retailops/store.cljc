(ns retailops.store
  "SSoT for the community-retail actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/retailops/store_contract_test.clj), which is the whole point:
  the actor, the Retail Governor and the audit ledger never know which
  SSoT they run on.

  Unlike the repair-shop-cluster's own `ticket` entity, the primary
  entity here is an `order` -- a POS sale line or a supplier reorder
  in progress, distinguished by its own `:kind` (`:sale` | `:reorder`).
  This still fits the SAME dual-actuation shape every prior sibling
  uses (two real-world acts on the SAME entity type, each with its own
  history collection, sequence counter and dedicated double-actuation-
  guard boolean, `:sale-posted?`/`:reorder-committed?`, never a
  `:status` value) -- it is simply that a given order's `:kind` means
  only ONE of those two booleans is ever meaningfully exercised for
  that order, the same 'both booleans always present, only one
  relevant' pattern every ticket in this fleet already uses for
  conditional fields (e.g. `specialtyrepair`/9529's own
  `:involves-precious-metal-work?`).

  The ledger stays append-only on every backend: 'which order was
  screened for an invalid EAN-13 or a price outside its own declared
  band, which sale was posted, which reorder was committed, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a customer or supplier trusting a
  retail shop needs, and the evidence an operator needs if a sale or a
  reorder is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [retailops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (order [s id])
  (all-orders [s])
  (assessment-of [s order-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (sale-history [s] "the append-only sale-posting history (retailops.registry drafts)")
  (reorder-history [s] "the append-only reorder-commitment history (retailops.registry drafts)")
  (next-sale-sequence [s jurisdiction] "next sale-number sequence for a jurisdiction")
  (next-reorder-sequence [s jurisdiction] "next reorder-number sequence for a jurisdiction")
  (order-already-sold? [s order-id] "has this order's sale already been posted?")
  (order-already-reordered? [s order-id] "has this order's reorder already been committed?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-orders [s orders] "replace/seed the order directory (map id->order)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained order set covering both actuation
  lifecycles (sale posting, reorder commitment) plus the governor's
  own new checks, so the actor + tests run offline."
  []
  {:orders
   {"order-1" {:id "order-1" :kind :sale :sku-id "sku-1" :sku-name "Rice 5kg bag"
                :ean13 "4006381333931" :unit-price 10.0 :quantity 2 :claimed-total 20.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 50 :reorder-at 10
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}
    "order-2" {:id "order-2" :kind :sale :sku-id "sku-2" :sku-name "Canned soup"
                :ean13 "4006381333931" :unit-price 3.0 :quantity 4 :claimed-total 12.0
                :price-band-min 1.0 :price-band-max 5.0
                :current-stock 80 :reorder-at 20
                :sale-posted? false :reorder-committed? false
                :jurisdiction "ATL" :status :intake}
    "order-3" {:id "order-3" :kind :sale :sku-id "sku-3" :sku-name "Cooking oil 1L"
                :ean13 "4006381333931" :unit-price 10.0 :quantity 2 :claimed-total 25.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 40 :reorder-at 10
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}
    "order-4" {:id "order-4" :kind :sale :sku-id "sku-4" :sku-name "Instant noodles (12-pack)"
                :ean13 "4006381333932" :unit-price 10.0 :quantity 2 :claimed-total 20.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 60 :reorder-at 15
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}
    "order-5" {:id "order-5" :kind :sale :sku-id "sku-5" :sku-name "Fresh milk 1L"
                :ean13 "4006381333931" :unit-price 20.0 :quantity 2 :claimed-total 40.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 30 :reorder-at 10
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}
    "order-6" {:id "order-6" :kind :reorder :sku-id "sku-6" :sku-name "Bottled water 24-pack"
                :ean13 "4006381333931" :unit-price 8.0 :quantity 10 :claimed-total 80.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 50 :reorder-at 10
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}
    "order-7" {:id "order-7" :kind :reorder :sku-id "sku-7" :sku-name "Toilet paper (bulk pack)"
                :ean13 "4006381333931" :unit-price 12.0 :quantity 20 :claimed-total 240.0
                :price-band-min 5.0 :price-band-max 15.0
                :current-stock 8 :reorder-at 10
                :sale-posted? false :reorder-committed? false
                :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- post-sale!
  "Backend-agnostic `:order/mark-sold` -- looks up the order via the
  protocol and drafts the sale-posting record, and returns {:result ..
  :order-patch ..} for the caller to persist."
  [s order-id]
  (let [o (order s order-id)
        seq-n (next-sale-sequence s (:jurisdiction o))
        result (registry/register-sale-completion order-id (:jurisdiction o) seq-n)]
    {:result result
     :order-patch {:sale-posted? true
                   :sale-number (get result "sale_number")}}))

(defn- commit-reorder!
  "Backend-agnostic `:order/mark-reordered` -- looks up the order via
  the protocol and drafts the reorder-commitment record, and returns
  {:result .. :order-patch ..} for the caller to persist."
  [s order-id]
  (let [o (order s order-id)
        seq-n (next-reorder-sequence s (:jurisdiction o))
        result (registry/register-reorder-commitment order-id (:jurisdiction o) seq-n)]
    {:result result
     :order-patch {:reorder-committed? true
                   :reorder-number (get result "reorder_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (order [_ id] (get-in @a [:orders id]))
  (all-orders [_] (sort-by :id (vals (:orders @a))))
  (assessment-of [_ order-id] (get-in @a [:assessments order-id]))
  (ledger [_] (:ledger @a))
  (sale-history [_] (:sales @a))
  (reorder-history [_] (:reorders @a))
  (next-sale-sequence [_ jurisdiction] (get-in @a [:sale-sequences jurisdiction] 0))
  (next-reorder-sequence [_ jurisdiction] (get-in @a [:reorder-sequences jurisdiction] 0))
  (order-already-sold? [_ order-id] (boolean (get-in @a [:orders order-id :sale-posted?])))
  (order-already-reordered? [_ order-id] (boolean (get-in @a [:orders order-id :reorder-committed?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (swap! a update-in [:orders (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :order/mark-sold
      (let [order-id (first path)
            {:keys [result order-patch]} (post-sale! s order-id)
            jurisdiction (:jurisdiction (order s order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sale-sequences jurisdiction] (fnil inc 0))
                       (update-in [:orders order-id] merge order-patch)
                       (update :sales registry/append result))))
        result)

      :order/mark-reordered
      (let [order-id (first path)
            {:keys [result order-patch]} (commit-reorder! s order-id)
            jurisdiction (:jurisdiction (order s order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:reorder-sequences jurisdiction] (fnil inc 0))
                       (update-in [:orders order-id] merge order-patch)
                       (update :reorders registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-orders [s orders] (when (seq orders) (swap! a assoc :orders orders)) s))

(defn seed-db
  "A MemStore seeded with the demo order set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :sale-sequences {} :sales []
                           :reorder-sequences {} :reorders []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts,
  sale/reorder records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:order/id                {:db/unique :db.unique/identity}
   :assessment/order-id     {:db/unique :db.unique/identity}
   :ledger/seq              {:db/unique :db.unique/identity}
   :sale/seq                {:db/unique :db.unique/identity}
   :reorder/seq             {:db/unique :db.unique/identity}
   :sale-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :reorder-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- order->tx [{:keys [id kind sku-id sku-name ean13 unit-price quantity claimed-total
                         price-band-min price-band-max current-stock reorder-at
                         sale-posted? reorder-committed?
                         jurisdiction status sale-number reorder-number]}]
  (cond-> {:order/id id}
    kind                                            (assoc :order/kind kind)
    sku-id                                            (assoc :order/sku-id sku-id)
    sku-name                                            (assoc :order/sku-name sku-name)
    ean13                                                 (assoc :order/ean13 ean13)
    unit-price                                              (assoc :order/unit-price unit-price)
    quantity                                                  (assoc :order/quantity quantity)
    claimed-total                                               (assoc :order/claimed-total claimed-total)
    price-band-min                                                (assoc :order/price-band-min price-band-min)
    price-band-max                                                  (assoc :order/price-band-max price-band-max)
    current-stock                                                     (assoc :order/current-stock current-stock)
    reorder-at                                                          (assoc :order/reorder-at reorder-at)
    (some? sale-posted?)                                                  (assoc :order/sale-posted? sale-posted?)
    (some? reorder-committed?)                                              (assoc :order/reorder-committed? reorder-committed?)
    jurisdiction                                                              (assoc :order/jurisdiction jurisdiction)
    status                                                                      (assoc :order/status status)
    sale-number                                                                  (assoc :order/sale-number sale-number)
    reorder-number                                                                (assoc :order/reorder-number reorder-number)))

(def ^:private order-pull
  [:order/id :order/kind :order/sku-id :order/sku-name :order/ean13 :order/unit-price
   :order/quantity :order/claimed-total :order/price-band-min :order/price-band-max
   :order/current-stock :order/reorder-at :order/sale-posted? :order/reorder-committed?
   :order/jurisdiction :order/status :order/sale-number :order/reorder-number])

(defn- pull->order [m]
  (when (:order/id m)
    {:id (:order/id m) :kind (:order/kind m) :sku-id (:order/sku-id m) :sku-name (:order/sku-name m)
     :ean13 (:order/ean13 m) :unit-price (:order/unit-price m) :quantity (:order/quantity m)
     :claimed-total (:order/claimed-total m)
     :price-band-min (:order/price-band-min m) :price-band-max (:order/price-band-max m)
     :current-stock (:order/current-stock m) :reorder-at (:order/reorder-at m)
     :sale-posted? (boolean (:order/sale-posted? m))
     :reorder-committed? (boolean (:order/reorder-committed? m))
     :jurisdiction (:order/jurisdiction m) :status (:order/status m)
     :sale-number (:order/sale-number m) :reorder-number (:order/reorder-number m)}))

(defrecord DatomicStore [conn]
  Store
  (order [_ id]
    (pull->order (d/pull (d/db conn) order-pull [:order/id id])))
  (all-orders [_]
    (->> (d/q '[:find [?id ...] :where [?e :order/id ?id]] (d/db conn))
         (map #(pull->order (d/pull (d/db conn) order-pull [:order/id %])))
         (sort-by :id)))
  (assessment-of [_ order-id]
    (dec* (d/q '[:find ?p . :in $ ?oid
                :where [?a :assessment/order-id ?oid] [?a :assessment/payload ?p]]
              (d/db conn) order-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (sale-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :sale/seq ?s] [?e :sale/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (reorder-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :reorder/seq ?s] [?e :reorder/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sale-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sale-sequence/jurisdiction ?j] [?e :sale-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-reorder-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :reorder-sequence/jurisdiction ?j] [?e :reorder-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (order-already-sold? [s order-id]
    (boolean (:sale-posted? (order s order-id))))
  (order-already-reordered? [s order-id]
    (boolean (:reorder-committed? (order s order-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (d/transact! conn [(order->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/order-id (first path) :assessment/payload (enc payload)}])

      :order/mark-sold
      (let [order-id (first path)
            {:keys [result order-patch]} (post-sale! s order-id)
            jurisdiction (:jurisdiction (order s order-id))
            next-n (inc (next-sale-sequence s jurisdiction))]
        (d/transact! conn
                     [(order->tx (assoc order-patch :id order-id))
                      {:sale-sequence/jurisdiction jurisdiction :sale-sequence/next next-n}
                      {:sale/seq (count (sale-history s)) :sale/record (enc (get result "record"))}])
        result)

      :order/mark-reordered
      (let [order-id (first path)
            {:keys [result order-patch]} (commit-reorder! s order-id)
            jurisdiction (:jurisdiction (order s order-id))
            next-n (inc (next-reorder-sequence s jurisdiction))]
        (d/transact! conn
                     [(order->tx (assoc order-patch :id order-id))
                      {:reorder-sequence/jurisdiction jurisdiction :reorder-sequence/next next-n}
                      {:reorder/seq (count (reorder-history s)) :reorder/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-orders [s orders]
    (when (seq orders) (d/transact! conn (mapv order->tx (vals orders)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:orders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [orders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-orders s orders))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo order set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
