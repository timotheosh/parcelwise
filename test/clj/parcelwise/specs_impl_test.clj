(ns parcelwise.specs-impl-test
  "Implementer-owned unit tests covering implementation details of
  parcelwise.specs that the independent behavioral test suite
  (specs_test.clj) did not exercise: spec 6.2's explicit 'finite'
  requirement for parcel measurements (NaN/Infinity, not just
  zero/negative), and additional shapes of the carrier-result spec
  (8.2) beyond the specific cases the test-designer chose to cover."
  (:require
    [clojure.test :refer :all]
    [clojure.spec.alpha :as s]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

(deftest parcel-finite-number-test
  (testing "spec 6.2: parcel values must be finite -- NaN and Infinity are rejected even though they are technically numbers"
    (doseq [field [:weight-kg :length-cm :width-cm :height-cm]]
      (is (not (s/valid? ::specs/parcel (ts/parcel {field Double/NaN})))
          (str field " NaN should be invalid"))
      (is (not (s/valid? ::specs/parcel (ts/parcel {field Double/POSITIVE_INFINITY})))
          (str field " Infinity should be invalid")))))

(deftest normalized-quote-total-amount-finite-number-test
  (testing "spec 6.4/11: ::total-amount must be finite -- NaN and Infinity are rejected even though they are technically numbers"
    (is (not (s/valid? ::specs/normalized-quote (ts/normalized-quote {:total-amount Double/NaN})))
        "NaN total-amount should be invalid")
    (is (not (s/valid? ::specs/normalized-quote (ts/normalized-quote {:total-amount Double/POSITIVE_INFINITY})))
        "Infinity total-amount should be invalid")))

(deftest carrier-result-shape-boundary-test
  (testing "an :ok result missing :rates is invalid"
    (is (not (s/valid? ::specs/carrier-result {:status :ok}))))

  (testing "an :error result missing :error-type is invalid"
    (is (not (s/valid? ::specs/carrier-result {:status :error}))))

  (testing "explain-data reports a problem for an invalid carrier-result"
    (is (some? (s/explain-data ::specs/carrier-result {:status :maybe})))))
