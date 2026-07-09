(ns retailops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [retailops.registry :as r]))

;; ----------------------------- ean13-valid? -----------------------------

(deftest ean13-valid-delegates-to-the-capability-library
  (is (r/ean13-valid? "4006381333931"))
  (is (not (r/ean13-valid? "4006381333932"))))

;; ----------------------------- sale-total-matches-claim? -----------------------------

(deftest matches-when-claim-equals-recompute
  (is (r/sale-total-matches-claim?
       {:sku-id "sku-1" :unit-price 10.0 :quantity 2 :claimed-total 20.0})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/sale-total-matches-claim?
            {:sku-id "sku-1" :unit-price 10.0 :quantity 2 :claimed-total 25.0}))))

(deftest compute-sale-total-is-a-flat-quantity-times-unit-price
  (is (= 20.0 (r/compute-sale-total {:sku-id "sku-1" :unit-price 10.0 :quantity 2}))))

;; ----------------------------- price-within-band? -----------------------------

(deftest price-within-band-when-inside-range
  (is (r/price-within-band? {:unit-price 10.0 :price-band-min 5.0 :price-band-max 15.0})))

(deftest price-outside-band-when-above-max
  (is (not (r/price-within-band? {:unit-price 20.0 :price-band-min 5.0 :price-band-max 15.0}))))

;; ----------------------------- needs-reorder? -----------------------------

(deftest needs-reorder-when-stock-at-or-below-threshold
  (is (r/needs-reorder? {:sku-id "sku-7" :current-stock 8 :reorder-at 10})))

(deftest does-not-need-reorder-when-stock-above-threshold
  (is (not (r/needs-reorder? {:sku-id "sku-6" :current-stock 50 :reorder-at 10}))))

;; ----------------------------- register-sale-completion -----------------------------

(deftest sale-is-a-draft-not-a-real-sale
  (let [result (r/register-sale-completion "order-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest sale-assigns-sale-number
  (let [result (r/register-sale-completion "order-1" "JPN" 7)]
    (is (= (get result "sale_number") "JPN-SAL-000007"))
    (is (= (get-in result ["record" "order_id"]) "order-1"))
    (is (= (get-in result ["record" "kind"]) "sale-posting-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest sale-validation-rules
  (is (thrown? Exception (r/register-sale-completion "" "JPN" 0)))
  (is (thrown? Exception (r/register-sale-completion "order-1" "" 0)))
  (is (thrown? Exception (r/register-sale-completion "order-1" "JPN" -1))))

;; ----------------------------- register-reorder-commitment -----------------------------

(deftest reorder-is-a-draft-not-a-real-reorder
  (let [result (r/register-reorder-commitment "order-7" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest reorder-assigns-reorder-number
  (let [result (r/register-reorder-commitment "order-7" "JPN" 7)]
    (is (= (get result "reorder_number") "JPN-ROR-000007"))
    (is (= (get-in result ["record" "order_id"]) "order-7"))
    (is (= (get-in result ["record" "kind"]) "reorder-commitment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest reorder-validation-rules
  (is (thrown? Exception (r/register-reorder-commitment "" "JPN" 0)))
  (is (thrown? Exception (r/register-reorder-commitment "order-7" "" 0)))
  (is (thrown? Exception (r/register-reorder-commitment "order-7" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-sale-completion "order-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-sale-completion "order-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SAL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SAL-000001" (get-in hist2 [1 "record_id"])))))
