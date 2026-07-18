(ns retailops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Decision Rule, published in `docs/business-model.md` BEFORE this
  actor existed, implemented faithfully. The single invariant under
  test:

    RetailOps-LLM never posts a sale or commits a reorder the Retail
    Governor would reject, `:sale/post`/`:reorder/commit` NEVER
    auto-commit at any phase, `:order/intake` (no direct capital risk)
    MAY auto-commit when clean, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [retailops.store :as store]
            [retailops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :shop-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :order/intake :subject "order-1"
                   :patch {:id "order-1" :sku-name "Rice 5kg bag"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Rice 5kg bag" (:sku-name (store/order db "order-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "order-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "order-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "order-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "order-1")) "no assessment written"))))

(deftest sale-post-without-assessment-is-held
  (testing "sale/post before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :sale/post :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest sale-total-mismatch-is-held
  (testing "a claimed sale total that doesn't equal quantity x unit-price -> HOLD (the ground-truth-recompute discipline every sibling's parts-cost check establishes, reapplied to a retail sale line)"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "order-3")
          res (exec-op actor "t5" {:op :sale/post :subject "order-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:sale-total-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/sale-history db))))))

(deftest ean13-invalid-is-held-and-unoverridable
  (testing "an invalid EAN-13 checksum on an order's own SKU -> HOLD, and never reaches request-approval -- the 71st unconditional-evaluation-discipline grounding overall, reusing the capability library's own kotoba.retail/ean13-valid? rather than reimplementing the checksum"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "order-4")
          res (exec-op actor "t6" {:op :sale/post :subject "order-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:ean13-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/sale-history db))))))

(deftest price-band-violation-is-held-and-unoverridable
  (testing "a unit price outside the SKU's own declared price band -> HOLD, and never reaches request-approval -- the FLAGSHIP genuinely new check this vertical adds, the 72nd unconditional-evaluation-discipline grounding overall, grounded in the US NIST Handbook 130, the UK's Price Marking Order 2004, Germany's Preisangabenverordnung and Japan's own 計量法 (see this actor's governor ns docstring / the full accumulated ADR-0001 chain: parksafety's ADR-2607071922 Decision 5 through leathergoods's and ictrepair's own)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "order-5")
          res (exec-op actor "t7" {:op :sale/post :subject "order-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:price-band-violation} (-> (store/ledger db) last :basis)))
      (is (empty? (store/sale-history db))))))

(deftest reorder-threshold-mismatch-is-held
  (testing "a reorder proposed on stock that is NOT actually at or below its own reorder threshold -> HOLD (the same ground-truth-recompute discipline as sale-total-mismatch, reapplied to a stock-level fact)"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "order-6")
          res (exec-op actor "t8" {:op :reorder/commit :subject "order-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:reorder-threshold-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reorder-history db))))))

(deftest sale-post-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, matching-total, valid-barcode, in-band-price order still ALWAYS interrupts for human approval -- actuation/post-sale is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "order-1")
          r1 (exec-op actor "t9" {:op :sale/post :subject "order-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, sale record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:sale-posted? (store/order db "order-1"))))
          (is (= 1 (count (store/sale-history db))) "one draft sale record"))))))

(deftest reorder-commit-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, genuinely-needed reorder still ALWAYS interrupts for human approval -- actuation/commit-reorder is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "order-7")
          r1 (exec-op actor "t10" {:op :reorder/commit :subject "order-7"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, reorder record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:reorder-committed? (store/order db "order-7"))))
          (is (= 1 (count (store/reorder-history db))) "one draft reorder record"))))))

(deftest sale-post-double-post-is-held
  (testing "posting the same order's sale twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "order-1")
          _ (exec-op actor "t11a" {:op :sale/post :subject "order-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :sale/post :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-sold} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/sale-history db))) "still only the one earlier sale"))))

(deftest reorder-commit-double-commit-is-held
  (testing "committing the same order's reorder twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t12pre" "order-7")
          _ (exec-op actor "t12a" {:op :reorder/commit :subject "order-7"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :reorder/commit :subject "order-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-reordered} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/reorder-history db))) "still only the one earlier reorder"))))

;; ----------------------------- cross-actor handoff receipt (jsic-4721 -> isic-4711) -----------------------------
;;
;; superproject ADR-2800000500. `:reorder/receive` is the receiving-side
;; counterpart to `:reorder/commit` -- a pure directory-upsert
;; (`:order/intake`-shaped, `:stake nil`) that may auto-commit at phase
;; 3 when clean, but a HARD cold-chain-handoff mismatch always holds
;; regardless.

(def ^:private frozen-fish-handoff
  "A handoff record an upstream cold-chain 3PL (e.g. cloud-itonami-
  jsic-4721) issues for a deep-frozen delivery."
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-jsic-4721"
   :handoff/batch-id "lot-001"
   :handoff/product-type-id :coldchain/f4-deep-frozen
   :handoff/cold-chain-temp-min-c -22.0
   :handoff/cold-chain-temp-max-c -18.0
   :handoff/quantity-kg 80.0
   :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"})

(deftest reorder-receive-handoff-incompatible-with-storage-zone-is-held
  (testing "a deep-frozen handoff placed into the refrigerated zone -> HOLD (temperature-tier mismatch)"
    (let [[db actor] (fresh)
          res (exec-op actor "t13"
                      {:op :reorder/receive :subject "order-7"
                       :patch {:id "order-7" :handoff frozen-fish-handoff :storage-zone-id :refrigerated}}
                      operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:handoff-cold-chain-window-incompatible-with-storage-zone} (-> (store/ledger db) first :basis))))))

(deftest reorder-receive-handoff-compatible-with-storage-zone-auto-commits
  (testing "a deep-frozen handoff placed into the frozen zone -> auto-commit at phase 3 (no capital risk)"
    (let [[db actor] (fresh)
          res (exec-op actor "t14"
                      {:op :reorder/receive :subject "order-7"
                       :patch {:id "order-7" :handoff frozen-fish-handoff :storage-zone-id :frozen}}
                      operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :frozen (:storage-zone-id (store/order db "order-7"))) "SSoT actually updated"))))

(deftest reorder-receive-without-handoff-auto-commits
  (testing "a :reorder/receive proposal without a :handoff record is unaffected (backward compatible)"
    (let [[db actor] (fresh)
          res (exec-op actor "t15"
                      {:op :reorder/receive :subject "order-6" :patch {:id "order-6" :current-stock 60}}
                      operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 60 (:current-stock (store/order db "order-6")))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :order/intake :subject "order-1"
                          :patch {:id "order-1" :sku-name "Rice 5kg bag"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "order-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
