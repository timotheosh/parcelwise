(ns parcelwise.domain.calculations-test
  "Slice 1 behavioral tests for spec sections 7.1 (dimensional weight) and
  7.2 (billable weight), and AC3/AC4."
  (:require
    [clojure.test :refer :all]
    [parcelwise.domain.calculations :as calc]))

(deftest dimensional-weight-divisor-test
  (testing "the divisor is a named domain constant equal to 5000 (spec 7.1), not a bare literal scattered through the code"
    (is (= 5000M calc/dimensional-weight-divisor))))

(deftest dimensional-weight-kg-test
  (testing "computes (length-cm x width-cm x height-cm) / divisor"
    (are [length width height expected]
         (= expected (calc/dimensional-weight-kg length width height))
      ;; spec section 6.2's own parcel example: dimensional (1.8) < actual (3.2)
      30.0M 20.0M 15.0M 1.8M
      ;; a case where dimensional (24.0) clearly exceeds a plausible actual weight
      60.0M 50.0M 40.0M 24.0M
      ;; a case chosen so dimensional (5.0) can equal an actual weight exactly
      50.0M 50.0M 10.0M 5.0M))

  (testing "returns a BigDecimal, never binary floating point (spec 6.2: internal calculations must not use binary floating-point for money; parity applies to weight used in billing)"
    (is (decimal? (calc/dimensional-weight-kg 30.0M 20.0M 15.0M)))))

(deftest billable-weight-kg-test
  (testing "AC3: dimensional weight greater than actual weight -> billable weight equals the dimensional weight"
    (is (= 24.0M (calc/billable-weight-kg 5.0M 24.0M))))

  (testing "AC4: actual weight greater than dimensional weight -> billable weight equals the actual weight"
    (is (= 3.2M (calc/billable-weight-kg 3.2M 1.8M))))

  (testing "boundary: actual weight equal to dimensional weight -> billable weight equals that shared value"
    (is (= 5.0M (calc/billable-weight-kg 5.0M 5.0M)))))
