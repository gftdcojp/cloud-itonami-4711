(ns retailops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean order through
  intake -> jurisdiction assessment -> sale posting (escalate/approve/
  commit), then a SEPARATE clean order through intake -> jurisdiction
  assessment -> reorder commitment (escalate/approve/commit), then
  shows HARD-hold scenarios: a jurisdiction with no spec-basis, a sale
  total mismatch (verified first), an invalid EAN-13 barcode, a unit
  price outside the SKU's own declared price band, a reorder proposed
  on stock that is NOT actually at or below its own reorder threshold,
  a double sale-post, and a double reorder-commit.

  Unlike every prior repair-shop-cluster sibling, this actor's new
  checks (`ean13-invalid?`, `price-band-violation?`) are evaluated
  directly at `:sale/post` time rather than via a separate screening
  op -- a real POS system validates a barcode and a price at the
  point of sale itself, not as a discrete pre-screening ceremony, so
  no `:sku/screen`-style op was introduced. Each check is still
  exercised directly and independently below, one order per HARD-hold
  scenario, following the SAME 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline `parksafety`'s
  ADR-2607071922 Decision 5 and every sibling since establish."
  (:require [langgraph.graph :as g]
            [retailops.store :as store]
            [retailops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :shop-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== order/intake order-1 (JPN, clean sale) ==")
    (println (exec-op actor "t1" {:op :order/intake :subject "order-1"
                                  :patch {:id "order-1" :sku-name "Rice 5kg bag"}} operator))

    (println "== jurisdiction/assess order-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :jurisdiction/assess :subject "order-1"} operator))
    (println (approve! actor "t2"))

    (println "== sale/post order-1 (always escalates -- actuation/post-sale) ==")
    (let [r (exec-op actor "t3" {:op :sale/post :subject "order-1"} operator)]
      (println r)
      (println "-- human shop operator approves --")
      (println (approve! actor "t3")))

    (println "== order/intake order-7 (JPN, clean reorder) ==")
    (println (exec-op actor "t4" {:op :order/intake :subject "order-7"
                                  :patch {:id "order-7" :sku-name "Toilet paper (bulk pack)"}} operator))

    (println "== jurisdiction/assess order-7 (escalates -- human approves) ==")
    (println (exec-op actor "t5" {:op :jurisdiction/assess :subject "order-7"} operator))
    (println (approve! actor "t5"))

    (println "== reorder/commit order-7 (always escalates -- actuation/commit-reorder) ==")
    (let [r (exec-op actor "t6" {:op :reorder/commit :subject "order-7"} operator)]
      (println r)
      (println "-- human shop operator approves --")
      (println (approve! actor "t6")))

    (println "== jurisdiction/assess order-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :jurisdiction/assess :subject "order-2" :no-spec? true} operator))

    (println "== jurisdiction/assess order-3 (escalates -- human approves; sets up the sale-total-mismatch test) ==")
    (println (exec-op actor "t8" {:op :jurisdiction/assess :subject "order-3"} operator))
    (println (approve! actor "t8"))

    (println "== sale/post order-3 (claimed 25.0 vs recompute 20.0 -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :sale/post :subject "order-3"} operator))

    (println "== jurisdiction/assess order-4 (escalates -- human approves; sets up the EAN-13-invalid test) ==")
    (println (exec-op actor "t10" {:op :jurisdiction/assess :subject "order-4"} operator))
    (println (approve! actor "t10"))

    (println "== sale/post order-4 (invalid EAN-13 checksum -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :sale/post :subject "order-4"} operator))

    (println "== jurisdiction/assess order-5 (escalates -- human approves; sets up the price-band-violation test) ==")
    (println (exec-op actor "t12" {:op :jurisdiction/assess :subject "order-5"} operator))
    (println (approve! actor "t12"))

    (println "== sale/post order-5 (unit price 20.0 outside price band [5.0,15.0] -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :sale/post :subject "order-5"} operator))

    (println "== jurisdiction/assess order-6 (escalates -- human approves; sets up the reorder-threshold-mismatch test) ==")
    (println (exec-op actor "t14" {:op :jurisdiction/assess :subject "order-6"} operator))
    (println (approve! actor "t14"))

    (println "== reorder/commit order-6 (stock 50 above reorder-at 10 -> HARD hold) ==")
    (println (exec-op actor "t15" {:op :reorder/commit :subject "order-6"} operator))

    (println "== sale/post order-1 AGAIN (double-sale -> HARD hold) ==")
    (println (exec-op actor "t16" {:op :sale/post :subject "order-1"} operator))

    (println "== reorder/commit order-7 AGAIN (double-reorder -> HARD hold) ==")
    (println (exec-op actor "t17" {:op :reorder/commit :subject "order-7"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft sale-posting records ==")
    (doseq [r (store/sale-history db)] (println r))

    (println "== draft reorder-commitment records ==")
    (doseq [r (store/reorder-history db)] (println r))))
