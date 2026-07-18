(ns retailops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:sale/post`/`:reorder/commit` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [retailops.phase :as phase]))

(deftest sale-post-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real sale"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :sale/post))
          (str "phase " n " must not auto-commit :sale/post")))))

(deftest reorder-commit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real reorder"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :reorder/commit))
          (str "phase " n " must not auto-commit :reorder/commit")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":order/intake and :reorder/receive carry no direct capital risk -- auto-eligible; these are the ONLY auto-eligible ops in this domain"
    (is (= #{:order/intake :reorder/receive} (:auto (get phase/phases 3))))))

(deftest reorder-receive-never-auto-before-phase-3
  (testing "phase 1/2 require approval for :reorder/receive even though it is a low-risk logging op"
    (is (= :escalate (:disposition (phase/gate 1 {:op :reorder/receive} :commit))))
    (is (= :escalate (:disposition (phase/gate 2 {:op :reorder/receive} :commit))))
    (is (= :commit (:disposition (phase/gate 3 {:op :reorder/receive} :commit))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :order/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :sale/post} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :reorder/commit} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :order/intake} :commit)))))
