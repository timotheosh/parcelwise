(ns parcelwise.http.json-boundary-impl-test
  "Implementer-owned unit tests covering implementation-level boundary
  cases of parcelwise.http.json-boundary that the independent behavioral
  test suite (json_boundary_test.clj) does not exercise -- negative and
  zero decimal values, and a scale-independent encode/decode round trip.
  Does not touch or weaken the independent suite."
  (:require
    [clojure.test :refer :all]
    [parcelwise.http.json-boundary :as json-boundary]))

(deftest encode-decimal-negative-and-zero-test
  (testing "negative and zero BigDecimal values encode as plain, non-scientific strings"
    (is (= "-5.00" (json-boundary/encode-decimal -5.00M)))
    (is (= "0" (json-boundary/encode-decimal 0M)))
    (is (= "0.00" (json-boundary/encode-decimal 0.00M)))))

(deftest decode-decimal-string-negative-test
  (testing "a negative decimal string parses (business-validity, e.g. rejecting negatives, is the domain spec's job, not json-boundary's)"
    (is (= -3.2M (json-boundary/decode-decimal-string "-3.2")))))

(deftest encode-decode-round-trip-test
  (testing "encode-decimal composed with decode-decimal-string round-trips value-equal (scale-independent) for representative examples"
    (doseq [v [12.50M 1.25M 0.1M 100M -7.75M]]
      (is (= v (json-boundary/decode-decimal-string (json-boundary/encode-decimal v)))))))
