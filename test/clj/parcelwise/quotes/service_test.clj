(ns parcelwise.quotes.service-test
  "Behavioral tests for parcelwise.quotes.service -- the orchestrator that
  takes a validated domain quote-request plus a carrier adapter, calls
  fetch-rates, and on success runs the Slice-1 filter-valid-rates ->
  normalize-rate -> order-quotes pipeline; on carrier error, returns the
  carrier's error info for the HTTP layer to map (spec 9.3/9.4).

  API contract fixed by this test file (test-designer's public-API
  decision, per the same 'load-bearing open decision' precedent set in
  Slice 1's test-plan.md): `(service/get-quotes carrier-adapter provider
  quote-request)` => `{:status :ok :quotes [<normalized-quote>...]}` on
  carrier success, or `{:status :error :error-type <kind>}` (unchanged
  pass-through of the carrier's own error-type) on carrier failure. Takes
  `provider` as an explicit third argument, distinct from the carrier
  adapter itself, because parcelwise.carrier.protocol/CarrierAdapter has no
  method for reporting which provider keyword (:fake/:shippo) normalized
  quotes should be stamped with (spec 6.4's :provider field) --
  FakeCarrier's only field is :scenario, not :provider.

  Deliberately operates purely on domain-shaped data (parcelwise.test-support
  fixtures), not HTTP/JSON -- this is the Action boundary *between*
  quotes-handler and the Slice-1 domain calculations, not the HTTP boundary
  itself. Only requires already-implemented Slice 1 production namespaces
  plus the not-yet-implemented parcelwise.quotes.service, so this file's
  RED is attributable purely to the missing service namespace."
  (:require
    [clojure.test :refer :all]
    [parcelwise.carrier.fake :as fake]
    [parcelwise.quotes.service :as service]
    [parcelwise.test-support :as ts]))

(deftest normal-scenario-returns-ok-with-ordered-quotes-test
  (testing "AC1: a successful carrier response is normalized, filtered, and ordered into >= 3 quotes"
    (let [result (service/get-quotes (fake/fake-carrier :normal) :fake (ts/quote-request))]
      (is (= :ok (:status result)))
      (is (>= (count (:quotes result)) 3))
      (testing "quotes are sorted ascending by total-amount (spec 7.5), verified end to end through the service"
        (is (apply <= (map :total-amount (:quotes result)))))
      (testing "every quote is stamped with the given provider"
        (is (every? #(= :fake (:provider %)) (:quotes result))))
      (testing "billable-weight-kg is present and derived from the request's parcel, not the raw rate"
        (is (every? :billable-weight-kg (:quotes result)))))))

(deftest malformed-rate-scenario-filters-without-losing-valid-rates-test
  (testing "AC5, wired end to end through the service: a malformed rate is excluded, valid rates remain"
    (let [result (service/get-quotes (fake/fake-carrier :malformed-rate) :fake (ts/quote-request))]
      (is (= :ok (:status result)))
      (is (not (contains? (set (map :id (:quotes result))) "fake-broken")))
      (is (>= (count (:quotes result)) 3)))))

(deftest failure-scenario-returns-error-with-carrier-error-type-test
  (testing "AC6: a carrier failure is surfaced as a stable error result, error-type preserved unchanged"
    (let [result (service/get-quotes (fake/fake-carrier :failure) :fake (ts/quote-request))]
      (is (= {:status :error :error-type :upstream-error} result)))))

(deftest timeout-error-type-passed-through-unchanged-test
  (testing "AC6/spec 9.4: a :timeout carrier error is preserved, not collapsed or reclassified by the service layer"
    (let [result (service/get-quotes (ts/error-carrier :timeout) :fake (ts/quote-request))]
      (is (= {:status :error :error-type :timeout} result)))))

(deftest every-error-type-is-preserved-unchanged-test
  (testing "the service does not collapse the granular carrier error-type enum -- that's the HTTP boundary's job"
    (doseq [error-type [:auth-failure :rate-limited :validation-error :upstream-error :timeout]]
      (is (= {:status :error :error-type error-type}
             (service/get-quotes (ts/error-carrier error-type) :fake (ts/quote-request)))))))

(deftest empty-rates-scenario-returns-ok-with-empty-quotes-test
  (testing "AC7: a successful provider response with zero usable rates yields :ok with an empty quotes list, not an error"
    (let [result (service/get-quotes (ts/empty-rates-carrier) :fake (ts/quote-request))]
      (is (= {:status :ok :quotes []} result)))))

(deftest dimensional-weight-driven-request-uses-dimensional-billable-weight-test
  (testing "AC3, wired end to end: when dimensional weight exceeds actual weight, billable-weight-kg reflects that"
    (let [result (service/get-quotes (fake/fake-carrier :normal)
                                      :fake
                                      (ts/quote-request {:parcel (ts/dimensional-heavy-parcel)}))]
      (is (= :ok (:status result)))
      ;; dimensional-heavy-parcel: (60 x 50 x 40) / 5000 = 24.0kg dimensional vs 5.0kg actual
      (is (every? #(= 24.0M (:billable-weight-kg %)) (:quotes result))))))
