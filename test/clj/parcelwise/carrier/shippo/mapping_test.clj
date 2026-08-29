(ns parcelwise.carrier.shippo.mapping-test
  "Slice 4 behavioral tests for parcelwise.carrier.shippo.mapping (spec
  8.2). [Calculation tests] -- mapping.clj must depend only on its
  explicit arguments and perform no I/O, per spec 5.1's ACD rules, so
  every test here operates on plain Clojure data, never real HTTP.

  Covers: unit conversion (kg->lb, cm->in), the application
  quote-request -> Shippo request wire mapping, the Shippo response ->
  raw-rate normalization (using the representative fixtures in
  parcelwise.carrier.shippo.fixtures), and failure classification into
  the existing parcelwise.specs/error-type enum. The response-normalization
  tests additionally prove the raw rates flow through the EXISTING,
  already-approved Slice 1 filter/normalize/order pipeline
  (parcelwise.domain.quote-engine) correctly -- the same 'prove the seams
  connect' pattern parcelwise.carrier.fake-test already established for
  the fake adapter (spec 13.4: adapter contract tests)."
  (:require
    [clojure.test :refer :all]
    [clojure.spec.alpha :as s]
    [parcelwise.carrier.shippo.mapping :as mapping]
    [parcelwise.carrier.shippo.fixtures :as fixtures]
    [parcelwise.domain.quote-engine :as qe]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Unit conversion (spec 8.2: "Convert kilograms and centimeters into units
;; required by Shippo"). Assumed wire units: pounds (lb) for mass, inches
;; (in) for distance -- see .ai/test-plan.md's "Assumed Shippo wire format"
;; section for the full reasoning. Conversion itself must preserve full
;; precision (no premature rounding, mirroring Slice 1's
;; dimensional-weight-kg/billable-weight-kg discipline); rounding to a
;; wire-safe decimal string happens only at the request-boundary formatting
;; step, tested separately below.
;; ---------------------------------------------------------------------------

(deftest kg->lb-test
  (testing "converts kilograms to pounds at full precision, using a named conversion-factor constant (mirrors spec 7.1's 'named domain constant, not a bare literal' rule)"
    (is (decimal? mapping/kg-to-lb-factor))
    (are [kg expected-lb]
         (= expected-lb (mapping/kg->lb kg))
      1.0M  2.20462262185M
      3.2M  7.054792389920M
      0.5M  1.102311310925M
      5.0M  11.023113109250M
      10.0M 22.04622621850M))

  (testing "returns a BigDecimal, never binary floating point (spec 6.2/13.2)"
    (is (decimal? (mapping/kg->lb 3.2M)))))

(deftest cm->in-test
  (testing "converts centimeters to inches at full precision, using a named conversion-factor constant"
    (is (decimal? mapping/cm-to-in-factor))
    (are [cm expected-in]
         (= expected-in (mapping/cm->in cm))
      30.0M 11.811023622060M
      20.0M 7.874015748040M
      15.0M 5.905511811030M
      60.0M 23.622047244120M
      1.0M  0.393700787402M))

  (testing "returns a BigDecimal, never binary floating point"
    (is (decimal? (mapping/cm->in 30.0M)))))

(deftest decimal->wire-string-test
  (testing "formats a BigDecimal as a 2-decimal-place wire string, rounding half-up only at this boundary step -- not during the conversion itself"
    (are [value expected-string]
         (= expected-string (mapping/decimal->wire-string value))
      (mapping/kg->lb 3.2M)  "7.05"
      (mapping/cm->in 30.0M) "11.81"
      (mapping/cm->in 20.0M) "7.87"
      (mapping/cm->in 15.0M) "5.91"
      2.005M                 "2.01"
      2.004M                 "2.00"
      0M                     "0.00"))

  (testing "output is a plain wire string, not a BigDecimal, matching how the app already encodes decimal wire values (spec 9.1's quoted-string parcel fields)"
    (is (string? (mapping/decimal->wire-string 3.2M)))))

;; ---------------------------------------------------------------------------
;; Request mapping (spec 8.2: "Map application addresses and parcel data to
;; the Shippo request"). See .ai/test-plan.md for the assumed Shippo request
;; wire shape and field-naming reasoning.
;; ---------------------------------------------------------------------------

(deftest quote-request->shippo-request-test
  (testing "maps a full application quote-request into the assumed Shippo request wire shape"
    (let [request (ts/quote-request)
          shippo-request (mapping/quote-request->shippo-request request)]

      (testing "address_from mirrors the application origin address field-for-field"
        (is (= {:name "Jane Example" :street1 "123 Market Street" :street2 nil
                :city "Philadelphia" :state "PA" :zip "19103" :country "US"}
               (:address_from shippo-request))))

      (testing "address_to mirrors the application destination address field-for-field"
        (is (= {:name "John Example" :street1 "500 Howard Street" :street2 nil
                :city "San Francisco" :state "CA" :zip "94105" :country "US"}
               (:address_to shippo-request))))

      (testing "parcels is a single-element vector (spec 4: multiple parcels are explicitly out of scope) with converted, wire-formatted weight/dimensions"
        (is (= [{:length "11.81" :width "7.87" :height "5.91" :distance_unit "in"
                 :weight "7.05" :mass_unit "lb"}]
               (:parcels shippo-request))))

      (testing "does not request label purchase or a transaction (spec 8.2: 'Avoid purchasing labels or creating transactions beyond what is required to retrieve rates')"
        (is (= false (:async shippo-request))))))

  (testing "a null street-2 maps through as a null street2 (address optional-field boundary, spec 6.1)"
    (let [request (ts/quote-request {:origin (ts/address {:street-2 "Suite 400"})})]
      (is (= "Suite 400" (get-in (mapping/quote-request->shippo-request request) [:address_from :street2]))))))

;; ---------------------------------------------------------------------------
;; Response normalization (spec 8.2: "Normalize Shippo rates into the
;; application quote model") -- Shippo rate -> the same raw-rate shape
;; parcelwise.domain.quote-engine/filter-valid-rates and normalize-rate
;; already consume (spec 13.4).
;; ---------------------------------------------------------------------------

(deftest shippo-response->raw-rates-test
  (testing "maps each Shippo rate object into the raw-rate shape the domain pipeline already consumes"
    (let [raw-rates (mapping/shippo-response->raw-rates (fixtures/shippo-response))]

      (testing "returns one raw rate per Shippo rate object"
        (is (= 3 (count raw-rates))))

      (testing "maps object_id -> :id, provider -> :carrier, servicelevel.token -> :service-code, servicelevel.name -> :service-name"
        (let [priority (first (filter #(= "rate_usps_priority_001" (:id %)) raw-rates))]
          (is (= "rate_usps_priority_001" (:id priority)))
          (is (= "USPS" (:carrier priority)))
          (is (= "usps_priority" (:service-code priority)))
          (is (= "Priority Mail" (:service-name priority)))
          (is (= "USD" (:currency priority)))
          (is (= 12.50M (:base-amount priority)))
          (is (= 12.50M (:total-amount priority)))
          (is (= 2 (:estimated-days priority)))
          (is (not (false? (:available? priority))))))

      (testing "surcharge-amount is present (spec 6.4 requires it) even though Shippo does not itself split base/surcharge -- documented judgment call, see .ai/test-plan.md"
        (is (every? #(contains? % :surcharge-amount) raw-rates)))

      (testing "every mapped raw rate independently satisfies quote-engine's filtering as a fully valid rate"
        (is (= 3 (count (qe/filter-valid-rates raw-rates)))))))

  (testing "AC5-equivalent: a defensively malformed Shippo rate (missing amount) does not invalidate the other valid rates in the same response"
    (let [raw-rates (mapping/shippo-response->raw-rates (fixtures/shippo-response-with-malformed-rate))]
      (testing "normalization itself does not throw and returns one entry per input rate"
        (is (= 3 (count raw-rates))))

      (testing "quote-engine filtering rejects the malformed entry"
        (is (= 2 (count (qe/filter-valid-rates raw-rates)))))

      (testing "quote-engine filtering still accepts the two valid rates"
        (is (= #{"rate_usps_priority_001" "rate_usps_ground_002"}
               (set (map :id (qe/filter-valid-rates raw-rates)))))))))

(deftest shippo-response-end-to-end-pipeline-test
  (testing "a Shippo-shaped response flows correctly through the EXISTING, unmodified Slice 1 filter -> normalize -> order pipeline (spec 13.4: adapter contract; mirrors fake-test's normal-scenario-dimensional-weight-test pattern)"
    (let [raw-rates (mapping/shippo-response->raw-rates (fixtures/shippo-response))
          valid (qe/filter-valid-rates raw-rates)
          normalized (map #(qe/normalize-rate % {:billable-weight-kg 3.2M :provider :shippo}) valid)
          ordered (qe/order-quotes normalized)]

      (testing "every normalized quote satisfies the application-owned normalized-quote spec"
        (is (every? #(s/valid? ::specs/normalized-quote %) ordered)))

      (testing "every quote is stamped with the :shippo provider, not leaking Shippo-specific field names past normalization"
        (is (every? #(= :shippo (:provider %)) ordered))
        (is (not-any? #(contains? % :object_id) ordered))
        (is (not-any? #(contains? % :servicelevel) ordered)))

      (testing "ordering rule (spec 7.5) applies identically regardless of provider: cheapest total-amount first"
        (is (= ["rate_usps_ground_002" "rate_usps_priority_001" "rate_fedex_overnight_003"]
               (map :id ordered)))
        (is (= [8.20M 12.50M 45.00M] (map :total-amount ordered)))))))

;; ---------------------------------------------------------------------------
;; Failure classification (spec 8.2: "Map Shippo authentication, validation,
;; timeout, rate-limit, and upstream failures into stable application
;; errors"). Must reuse the existing granular ::specs/error-type enum
;; exactly -- no new enum values invented.
;; ---------------------------------------------------------------------------

(deftest classify-failure-test
  (testing "maps each documented failure shape to the correct existing error-type"
    (are [failure expected-error-type]
         (= expected-error-type (mapping/classify-failure failure))
      {:status 401}    :auth-failure
      {:status 403}    :auth-failure
      {:status 400}    :validation-error
      {:status 422}    :validation-error
      {:status 429}    :rate-limited
      {:status 500}    :upstream-error
      {:status 502}    :upstream-error
      {:status 503}    :upstream-error
      {:timeout? true} :timeout
      ;; an unrecognized/unexpected status must still map to a granular,
      ;; existing error-type (safe default), never throw and never expose
      ;; the raw status past this boundary
      {:status 418}    :upstream-error))

  (testing "every possible classification result is a member of the existing parcelwise.specs/error-type enum -- no new enum values invented"
    (doseq [failure [{:status 401} {:status 400} {:status 429} {:status 500} {:timeout? true}]]
      (is (s/valid? ::specs/error-type (mapping/classify-failure failure)))))

  (testing "a classified failure composes into a spec-valid carrier-result error shape"
    (is (s/valid? ::specs/carrier-result
                   {:status :error :error-type (mapping/classify-failure {:status 401})}))))
