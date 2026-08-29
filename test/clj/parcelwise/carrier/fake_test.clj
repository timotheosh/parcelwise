(ns parcelwise.carrier.fake-test
  "Slice 1 behavioral tests for spec section 8.1 (fake carrier adapter).
  Uses parcelwise.domain.calculations and parcelwise.domain.quote-engine
  together with the fake carrier for the scenarios that are inherently
  about the combined pipeline (dimensional-weight exercised end to end,
  and malformed-rate filtering end to end) -- filtering and the dimensional
  weight/billable weight calculations themselves are unit tested directly
  in quote_engine_test.clj and calculations_test.clj."
  (:require
    [clojure.test :refer :all]
    [clojure.spec.alpha :as s]
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.carrier.fake :as fake]
    [parcelwise.domain.calculations :as calc]
    [parcelwise.domain.quote-engine :as qe]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

(deftest fake-carrier-satisfies-protocol-test
  (testing "fake-carrier constructs something implementing the CarrierAdapter protocol"
    (is (satisfies? protocol/CarrierAdapter (fake/fake-carrier :normal)))))

(deftest normal-scenario-test
  (let [carrier (fake/fake-carrier :normal)
        request (ts/quote-request)
        result (protocol/fetch-rates carrier request)]

    (testing "returns a successful carrier result matching the carrier-result spec"
      (is (= :ok (:status result)))
      (is (s/valid? ::specs/carrier-result result)))

    (testing "returns at least three services (spec 8.1)"
      (is (>= (count (:rates result)) 3)))

    (testing "services have distinct prices (spec 8.1)"
      (is (= (count (:rates result))
             (count (distinct (map :total-amount (:rates result)))))))

    (testing "services have distinct delivery estimates (spec 8.1)"
      (is (= (count (:rates result))
             (count (distinct (map :estimated-days (:rates result)))))))

    (testing "is deterministic across repeated calls with the same request (spec 8.1: return deterministic data)"
      (is (= result (protocol/fetch-rates carrier request))))))

(deftest normal-scenario-dimensional-weight-test
  (testing "at least one service's billable weight, once combined with the domain calculations and normalization, is driven by dimensional weight rather than actual weight (spec 8.1: include at least one rate that exercises dimensional weight)"
    (let [request (ts/quote-request {:parcel (ts/dimensional-heavy-parcel)})
          {:keys [weight-kg length-cm width-cm height-cm]} (:parcel request)
          dimensional (calc/dimensional-weight-kg length-cm width-cm height-cm)
          billable (calc/billable-weight-kg weight-kg dimensional)
          carrier (fake/fake-carrier :normal)
          result (protocol/fetch-rates carrier request)
          valid-raw (qe/filter-valid-rates (:rates result))
          normalized (map #(qe/normalize-rate % {:billable-weight-kg billable :provider :fake})
                           valid-raw)]
      (is (< weight-kg dimensional)
          "fixture parcel must actually make dimensional weight exceed actual weight")
      (is (= dimensional billable)
          "billable weight must be driven by the dimensional weight, not the actual weight")
      (is (seq normalized))
      (is (every? #(= billable (:billable-weight-kg %)) normalized)))))

(deftest failure-scenario-test
  (testing "the :failure scenario reports the carrier-result error shape (spec 8.1: configurable to return a provider failure for tests)"
    (let [carrier (fake/fake-carrier :failure)
          result (protocol/fetch-rates carrier (ts/quote-request))]
      (is (= :error (:status result)))
      (is (contains? #{:timeout :auth-failure :rate-limited :validation-error :upstream-error}
                      (:error-type result)))
      (is (s/valid? ::specs/carrier-result result)))))

(deftest malformed-rate-scenario-test
  (testing "the :malformed-rate scenario includes a bad rate among otherwise-valid ones (spec 8.1: configurable to include a malformed rate)"
    (let [carrier (fake/fake-carrier :malformed-rate)
          result (protocol/fetch-rates carrier (ts/quote-request))]

      (testing "still reports overall provider success, with raw rates included"
        (is (= :ok (:status result)))
        (is (seq (:rates result))))

      (testing "quote-engine filtering rejects at least one of the returned rates"
        (is (< (count (qe/filter-valid-rates (:rates result)))
               (count (:rates result)))))

      (testing "quote-engine filtering still accepts at least one valid rate (AC5: malformed rate does not invalidate the rest)"
        (is (seq (qe/filter-valid-rates (:rates result))))))))
