(ns retailops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [retailops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :sale (:kind (store/order s "order-1"))))
      (is (= "JPN" (:jurisdiction (store/order s "order-1"))))
      (is (= 20.0 (:claimed-total (store/order s "order-1"))))
      (is (= "4006381333931" (:ean13 (store/order s "order-1"))))
      (is (= 25.0 (:claimed-total (store/order s "order-3"))))
      (is (= "4006381333932" (:ean13 (store/order s "order-4"))))
      (is (= 20.0 (:unit-price (store/order s "order-5"))))
      (is (= :reorder (:kind (store/order s "order-6"))))
      (is (= 50 (:current-stock (store/order s "order-6"))))
      (is (= 8 (:current-stock (store/order s "order-7"))))
      (is (false? (:sale-posted? (store/order s "order-1"))))
      (is (false? (:reorder-committed? (store/order s "order-7"))))
      (is (= ["order-1" "order-2" "order-3" "order-4" "order-5" "order-6" "order-7"]
             (mapv :id (store/all-orders s))))
      (is (nil? (store/assessment-of s "order-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/sale-history s)))
      (is (= [] (store/reorder-history s)))
      (is (zero? (store/next-sale-sequence s "JPN")))
      (is (zero? (store/next-reorder-sequence s "JPN")))
      (is (false? (store/order-already-sold? s "order-1")))
      (is (false? (store/order-already-reordered? s "order-7"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :order/upsert
                                 :value {:id "order-1" :sku-name "Rice 5kg bag"}})
        (is (= "Rice 5kg bag" (:sku-name (store/order s "order-1"))))
        (is (= 20.0 (:claimed-total (store/order s "order-1"))) "unrelated field preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["order-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "order-1"))))
      (testing "sale posting drafts a record and advances the sale sequence"
        (store/commit-record! s {:effect :order/mark-sold :path ["order-1"]})
        (is (= "JPN-SAL-000000" (get (first (store/sale-history s)) "record_id")))
        (is (= "sale-posting-draft" (get (first (store/sale-history s)) "kind")))
        (is (true? (:sale-posted? (store/order s "order-1"))))
        (is (= 1 (count (store/sale-history s))))
        (is (= 1 (store/next-sale-sequence s "JPN")))
        (is (true? (store/order-already-sold? s "order-1"))))
      (testing "reorder commitment drafts a record and advances the reorder sequence"
        (store/commit-record! s {:effect :order/mark-reordered :path ["order-7"]})
        (is (= "JPN-ROR-000000" (get (first (store/reorder-history s)) "record_id")))
        (is (= "reorder-commitment-draft" (get (first (store/reorder-history s)) "kind")))
        (is (true? (:reorder-committed? (store/order s "order-7"))))
        (is (= 1 (count (store/reorder-history s))))
        (is (= 1 (store/next-reorder-sequence s "JPN")))
        (is (true? (store/order-already-reordered? s "order-7"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/order s "nope")))
    (is (= [] (store/all-orders s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/sale-history s)))
    (is (= [] (store/reorder-history s)))
    (is (zero? (store/next-sale-sequence s "JPN")))
    (is (zero? (store/next-reorder-sequence s "JPN")))
    (store/with-orders s {"x" {:id "x" :kind :sale :sku-id "sku-x" :sku-name "n"
                               :ean13 "4006381333931" :unit-price 10.0 :quantity 1 :claimed-total 10.0
                               :price-band-min 5.0 :price-band-max 15.0
                               :current-stock 50 :reorder-at 10
                               :sale-posted? false :reorder-committed? false
                               :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:sku-name (store/order s "x"))))))
