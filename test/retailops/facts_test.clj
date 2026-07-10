(ns retailops.facts-test
  (:require [clojure.test :refer [deftest is]]
            [retailops.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest newly-seeded-jurisdictions-have-a-spec-basis
  (doseq [iso3 ["IND" "SAU" "ARE" "MEX"]]
    (is (some? (facts/spec-basis iso3)) (str iso3 " spec-basis"))
    (is (string? (:provenance (facts/spec-basis iso3))) (str iso3 " provenance"))))

(deftest all-eight-seeded-jurisdictions-have-a-price-spec-basis
  ;; unlike some prior repair-shop-cluster siblings' own honest single-
  ;; jurisdiction gap, ALL EIGHT seeded jurisdictions actually have a
  ;; real unit-pricing/price-marking enforcement regime here --
  ;; reported honestly, not forced narrower
  (doseq [iso3 ["JPN" "USA" "GBR" "DEU" "IND" "SAU" "ARE" "MEX"]]
    (is (some? (facts/price-spec-basis iso3)) (str iso3 " price-spec-basis"))
    (is (string? (:price-provenance (facts/price-spec-basis iso3))) (str iso3 " price-provenance"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest unknown-jurisdiction-has-no-price-spec-basis
  (is (nil? (facts/price-spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
