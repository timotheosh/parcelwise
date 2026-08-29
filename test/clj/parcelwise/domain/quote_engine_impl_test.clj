(ns parcelwise.domain.quote-engine-impl-test
  "Implementer-owned unit tests covering implementation details of
  parcelwise.domain.quote-engine that the independent behavioral test
  suite (quote_engine_test.clj) deliberately left uncovered as an
  unresolved ambiguity in the requirements doc (spec 7.4 lists 'missing
  or invalid' and 'negative' total-amount as exclusion rules but does not
  state whether an exact zero total is valid or invalid).

  This file documents and locks in the interpretation actually
  implemented -- a zero total-amount is neither missing/non-numeric nor
  negative, so it is treated as valid -- without touching or weakening
  the independently authored behavioral tests. If this interpretation is
  wrong, it is a requirements/test-design question, not something to
  quietly change here."
  (:require
    [clojure.test :refer :all]
    [parcelwise.domain.quote-engine :as qe]
    [parcelwise.test-support :as ts]))

(deftest zero-total-amount-boundary-test
  (testing "spec 7.4 only excludes missing/invalid and negative totals; an exact zero total is therefore kept as valid"
    (is (= 1 (count (qe/filter-valid-rates [(ts/raw-rate {:total-amount 0M})]))))))

(deftest non-finite-total-amount-excluded-test
  (testing "spec 7.4: a NaN or Infinite total-amount is 'invalid' and must be excluded, even though it satisfies number?"
    (is (= 0 (count (qe/filter-valid-rates [(ts/raw-rate {:total-amount Double/NaN})])))
        "NaN total-amount should be excluded")
    (is (= 0 (count (qe/filter-valid-rates [(ts/raw-rate {:total-amount Double/POSITIVE_INFINITY})])))
        "positive Infinity total-amount should be excluded")
    (is (= 0 (count (qe/filter-valid-rates [(ts/raw-rate {:total-amount Double/NEGATIVE_INFINITY})])))
        "negative Infinity total-amount should be excluded")))
