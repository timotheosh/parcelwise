(ns parcelwise.http.quotes-handler-test
  "Behavioral/route-level tests for parcelwise.http.quotes-handler -- the
  POST /api/quotes handler, covering AC1, AC2, AC6, AC7, required-field
  validation, malformed numeric strings, and stable API error shapes
  (spec sections 9, 11, 12, 13.3).

  API contract fixed by this test file: `(quotes-handler/handler
  carrier-adapter)` returns a plain ring handler function. Tests build a
  minimal reitit-ring app around it via
  parcelwise.http.test-support/quotes-test-app, injecting the exact
  carrier-adapter (real FakeCarrier scenarios, or the test-only stubs from
  parcelwise.test-support) each test needs -- independent of whatever
  env-driven mount wiring parcelwise.startup.carrier-config uses for the
  real app (that full end-to-end wiring, using the default fake/:normal
  path, is separately covered by parcelwise.routes.quotes-route-test via
  (parcelwise.handler/app))."
  (:require
    [clojure.test :refer :all]
    [parcelwise.carrier.fake :as fake]
    [parcelwise.http.test-support :as http-ts]
    [parcelwise.test-support :as ts]
    [ring.mock.request :refer :all]))

(defn- post-quotes [ring-app body]
  (ring-app (-> (request :post "/api/quotes")
                (json-body body))))

(defn- decode [response]
  (http-ts/decode-body response))

;; ---------------------------------------------------------------------------
;; AC1 -- valid request -> 200
;; ---------------------------------------------------------------------------

(deftest ac1-valid-request-returns-200-with-ordered-quotes-test
  (let [app (http-ts/quotes-test-app (fake/fake-carrier :normal))
        response (post-quotes app (ts/json-quote-request))]
    (testing "HTTP 200"
      (is (= 200 (:status response))))
    (testing "Content-Type: application/json"
      (is (re-find #"application/json" (get-in response [:headers "Content-Type"]))))
    (let [body (decode response)]
      (testing "at least three normalized quotes (AC1)"
        (is (>= (count (:quotes body)) 3)))
      (testing "every required camelCase field is present on every quote (spec 6.4/9.1)"
        (doseq [quote (:quotes body)]
          (doseq [k [:id :carrier :serviceCode :serviceName :currency
                     :baseAmount :surchargeAmount :totalAmount
                     :billableWeightKg :provider]]
            (is (contains? quote k) (str k " missing from " quote)))))
      (testing "money/weight fields are decimal-pattern strings, not JSON numbers"
        (doseq [quote (:quotes body)]
          (doseq [k [:baseAmount :surchargeAmount :totalAmount :billableWeightKg]]
            (is (string? (get quote k)) (str k " must be a string")))))
      (testing "quotes are sorted ascending by total-amount end to end through the HTTP response (spec 7.5, AC1)"
        (is (= ["fake-economy" "fake-ground" "fake-express" "fake-overnight"]
               (map :id (:quotes body))))))))

;; ---------------------------------------------------------------------------
;; AC2 -- invalid parcel -> 400, all invalid fields reported, carrier not called
;; ---------------------------------------------------------------------------

(deftest ac2-zero-weight-returns-400-with-stable-error-shape-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request
                                     {:parcel (ts/json-parcel {:weightKg "0"})}))]
    (testing "HTTP 400"
      (is (= 400 (:status response))))
    (testing "Content-Type: application/json"
      (is (re-find #"application/json" (get-in response [:headers "Content-Type"]))))
    (testing "exact stable error envelope from spec 9.2"
      (is (= {:error {:code    "invalid-request"
                       :message "The quote request is invalid."
                       :fields  {:parcel.weightKg ["must be greater than zero"]}}}
             (decode response))))))

(deftest ac2-negative-dimensions-returns-400-with-all-fields-reported-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request
                                     {:parcel (ts/json-parcel {:lengthCm "-1" :widthCm "-2"})}))
        body (decode response)]
    (testing "HTTP 400, and every invalid field discovered in one pass (spec 9.2, AC2)"
      (is (= 400 (:status response)))
      (is (contains? (:fields (:error body)) :parcel.lengthCm))
      (is (contains? (:fields (:error body)) :parcel.widthCm)))))

(deftest ac2-carrier-adapter-is-not-called-for-invalid-request-test
  (testing "AC2: the carrier adapter must not be invoked for an invalid request -- proven by a stub that throws if invoked"
    (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
          response (post-quotes app (ts/json-quote-request {:parcel (ts/json-parcel {:weightKg "0"})}))]
      ;; if the carrier adapter were invoked, ts/never-called-carrier throws,
      ;; which would surface as something other than a clean 400 (typically
      ;; a 500, or an uncaught exception failing the test outright).
      (is (= 400 (:status response))))))

;; ---------------------------------------------------------------------------
;; Required-field validation
;; ---------------------------------------------------------------------------

(deftest missing-required-address-field-returns-400-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request {:origin (dissoc (ts/json-address) :name)}))
        body (decode response)]
    (is (= 400 (:status response)))
    (is (contains? (:fields (:error body)) :origin.name))))

(deftest blank-required-address-field-returns-400-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request {:destination (ts/json-destination-address {:city ""})}))
        body (decode response)]
    (is (= 400 (:status response)))
    (is (contains? (:fields (:error body)) :destination.city))))

(deftest malformed-country-code-returns-400-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request {:origin (ts/json-address {:countryCode "usa"})}))
        body (decode response)]
    (is (= 400 (:status response)))
    (is (contains? (:fields (:error body)) :origin.countryCode))))

;; ---------------------------------------------------------------------------
;; Malformed numeric strings at the HTTP boundary (spec 6.2)
;; ---------------------------------------------------------------------------

(deftest malformed-numeric-parcel-field-returns-400-not-500-test
  (let [app (http-ts/quotes-test-app (ts/never-called-carrier))
        response (post-quotes app (ts/json-quote-request {:parcel (ts/json-parcel {:weightKg "not-a-number"})}))
        body (decode response)]
    (testing "a malformed numeric string is a 400 field error, never a 500 or an unhandled exception"
      (is (= 400 (:status response)))
      (is (contains? (:fields (:error body)) :parcel.weightKg)))))

;; ---------------------------------------------------------------------------
;; AC6 -- provider failure -> stable application error, no raw details leaked
;; ---------------------------------------------------------------------------

(deftest ac6-timeout-returns-504-test
  (let [app (http-ts/quotes-test-app (ts/error-carrier :timeout))
        response (post-quotes app (ts/json-quote-request))]
    (is (= 504 (:status response)))
    (is (re-find #"application/json" (get-in response [:headers "Content-Type"])))
    (is (= {:error {:code "carrier-timeout" :message "The shipping-rate request timed out."}}
           (decode response)))))

(deftest ac6-non-timeout-errors-return-502-test
  (doseq [error-type [:upstream-error :auth-failure :rate-limited :validation-error]]
    (testing (str error-type " maps to 502 carrier-unavailable")
      (let [app (http-ts/quotes-test-app (ts/error-carrier error-type))
            response (post-quotes app (ts/json-quote-request))]
        (is (= 502 (:status response)))
        (is (= {:error {:code "carrier-unavailable" :message "Shipping rates are temporarily unavailable."}}
               (decode response)))))))

(deftest ac6-error-responses-never-expose-raw-provider-details-test
  (testing "the granular carrier error-type, Java exception class names, and stack traces never appear in the response body"
    (doseq [error-type [:upstream-error :auth-failure :rate-limited :validation-error :timeout]]
      (let [app (http-ts/quotes-test-app (ts/error-carrier error-type))
            response (post-quotes app (ts/json-quote-request))
            raw-body (slurp (:body response))]
        (is (not (re-find #"(?i)exception" raw-body)))
        (is (not (re-find #"(?i)auth-failure|rate-limited|validation-error|upstream-error" raw-body))
            "the granular carrier error-type enum value must not leak into the HTTP response body")
        (is (not (re-find #"clojure\.lang|java\.lang" raw-body)))))))

;; ---------------------------------------------------------------------------
;; AC7 -- empty valid-rate result -> 200 empty, not an error
;; ---------------------------------------------------------------------------

(deftest ac7-empty-rates-returns-200-empty-quotes-test
  (let [app (http-ts/quotes-test-app (ts/empty-rates-carrier))
        response (post-quotes app (ts/json-quote-request))]
    (is (= 200 (:status response)))
    (is (= {:quotes []} (decode response)))))
