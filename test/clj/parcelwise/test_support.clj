(ns parcelwise.test-support
  "Shared, reusable fixture builders for Slice 1 (pure domain) and Slice 2
  (fake-provider API) tests.

  These builders return plain Clojure maps matching the shapes described in
  docs/specifications/parcelwise.md section 6 (Domain Model) and section 9
  (HTTP API). Every builder accepts an optional map of overrides so
  individual tests can construct boundary/invalid variants without
  duplicating the full fixture literal.

  Reused across parcelwise.domain.calculations-test,
  parcelwise.domain.quote-engine-test, parcelwise.carrier.fake-test,
  parcelwise.specs-test (Slice 1), and parcelwise.http.json-boundary-test,
  parcelwise.http.error-mapping-test, parcelwise.http.quotes-handler-test,
  parcelwise.quotes.service-test, parcelwise.routes.quotes-route-test
  (Slice 2) to avoid divergent, hand-rolled fixture data per namespace.

  Only requires namespaces that already exist as of Slice 1
  (`parcelwise.carrier.protocol`) so that this file keeps loading cleanly
  --  and therefore keeps every already-GREEN Slice 1 test file GREEN --
  even while Slice 2's not-yet-created production namespaces are still
  missing. The HTTP-router test harness that *does* need to require the
  not-yet-created `parcelwise.http.quotes-handler` lives in the separate,
  isolated `test/clj/parcelwise/http/test_support.clj` instead, precisely
  to avoid that collateral compile failure -- see that file's docstring."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer [is]]
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.specs :as specs]))

(defn address
  "A valid origin-shaped address, per spec section 6.1's own example."
  ([] (address {}))
  ([overrides]
   (merge {:name         "Jane Example"
           :street-1     "123 Market Street"
           :street-2     nil
           :city         "Philadelphia"
           :region       "PA"
           :postal-code  "19103"
           :country-code "US"}
          overrides)))

(defn destination-address
  "A second, distinct valid address, useful when a test needs origin and
  destination to plainly differ."
  ([] (destination-address {}))
  ([overrides]
   (merge {:name         "John Example"
           :street-1     "500 Howard Street"
           :street-2     nil
           :city         "San Francisco"
           :region       "CA"
           :postal-code  "94105"
           :country-code "US"}
          overrides)))

(defn parcel
  "A valid parcel, per spec section 6.2's own example. Dimensional weight
  for these dimensions (30 x 20 x 15 / 5000 = 1.8kg) is below the actual
  weight (3.2kg) -- i.e. this fixture is NOT dimensional-weight-driven."
  ([] (parcel {}))
  ([overrides]
   (merge {:weight-kg 3.2M
           :length-cm 30.0M
           :width-cm  20.0M
           :height-cm 15.0M}
          overrides)))

(defn dimensional-heavy-parcel
  "A valid parcel whose dimensional weight clearly exceeds its actual
  weight: (60 x 50 x 40) / 5000 = 24.0kg dimensional vs 5.0kg actual.
  For exercising dimensional-weight-driven billable weight end to end."
  ([] (dimensional-heavy-parcel {}))
  ([overrides]
   (merge {:weight-kg 5.0M
           :length-cm 60.0M
           :width-cm  50.0M
           :height-cm 40.0M}
          overrides)))

(defn quote-request
  "A valid quote request: origin + destination + parcel."
  ([] (quote-request {}))
  ([overrides]
   (merge {:origin      (address)
           :destination (destination-address)
           :parcel      (parcel)}
          overrides)))

(defn raw-rate
  "A single valid raw carrier rate, in the shape a carrier adapter's
  fetch-rates is expected to return (pre-normalization, pre-filtering) --
  matching the field names of the normalized quote example in spec
  section 6.4, since :billable-weight-kg and :provider are the only fields
  normalization is expected to add from request/adapter context.
  :available? is an internal-only filtering signal, not part of the
  normalized-quote shape."
  ([] (raw-rate {}))
  ([overrides]
   (merge {:id              "fake-ground"
           :carrier         "Example Carrier"
           :service-code    "ground"
           :service-name    "Ground"
           :currency        "USD"
           :base-amount     12.50M
           :surcharge-amount 1.25M
           :total-amount    13.75M
           :estimated-days  4
           :available?      true}
          overrides)))

(defn normalized-quote
  "A single valid normalized quote, matching spec section 6.4's example
  exactly (raw-rate's defaults + the request-derived billable-weight-kg and
  provider fields)."
  ([] (normalized-quote {}))
  ([overrides]
   (merge {:id                 "fake-ground"
           :carrier            "Example Carrier"
           :service-code       "ground"
           :service-name       "Ground"
           :currency           "USD"
           :base-amount        12.50M
           :surcharge-amount   1.25M
           :total-amount       13.75M
           :estimated-days     4
           :billable-weight-kg 3.2M
           :provider           :fake}
          overrides)))

;; ---------------------------------------------------------------------------
;; Slice 2 -- HTTP-boundary (camelCase JSON) fixtures.
;;
;; These mirror the kebab-case domain fixtures above field-for-field, but in
;; the shape a client actually sends/receives as JSON over the wire (spec
;; section 9.1's own example request/response) -- decimal fields as
;; *strings*, camelCase keys, and (once decoded by muuntaja's default JSON
;; codec, confirmed by direct probe against parcelwise.middleware.formats)
;; keywordized camelCase keys such as :street1, :postalCode, :countryCode,
;; :weightKg. Kept in plain-data form (no dependency on any HTTP-boundary
;; production namespace) so they stay usable regardless of which Slice 2
;; production namespaces exist yet.
;; ---------------------------------------------------------------------------

(defn json-address
  "The camelCase/decoded-JSON shape of `address` above, matching spec
  section 9.1's origin example exactly."
  ([] (json-address {}))
  ([overrides]
   (merge {:name        "Jane Example"
           :street1     "123 Market Street"
           :street2     nil
           :city        "Philadelphia"
           :region      "PA"
           :postalCode  "19103"
           :countryCode "US"}
          overrides)))

(defn json-destination-address
  "The camelCase/decoded-JSON shape of `destination-address` above,
  matching spec section 9.1's destination example exactly."
  ([] (json-destination-address {}))
  ([overrides]
   (merge {:name        "John Example"
           :street1     "500 Howard Street"
           :street2     nil
           :city        "San Francisco"
           :region      "CA"
           :postalCode  "94105"
           :countryCode "US"}
          overrides)))

(defn json-parcel
  "The camelCase/decoded-JSON shape of `parcel` above -- decimal fields as
  *strings*, matching spec section 9.1's parcel example exactly."
  ([] (json-parcel {}))
  ([overrides]
   (merge {:weightKg "3.2"
           :lengthCm "30"
           :widthCm  "20"
           :heightCm "15"}
          overrides)))

(defn json-quote-request
  "A full, valid camelCase/decoded-JSON quote-request body, matching spec
  section 9.1's complete example request exactly."
  ([] (json-quote-request {}))
  ([overrides]
   (merge {:origin      (json-address)
           :destination (json-destination-address)
           :parcel      (json-parcel)}
          overrides)))

(defn json-normalized-quote
  "The camelCase/decoded-JSON shape of `normalized-quote` above -- decimal
  and weight fields as *strings*, `provider` as a JSON string, matching
  spec section 9.1's example response quote item exactly."
  ([] (json-normalized-quote {}))
  ([overrides]
   (merge {:id               "fake-ground"
           :carrier          "Example Carrier"
           :serviceCode      "ground"
           :serviceName      "Ground"
           :currency         "USD"
           :baseAmount       "12.50"
           :surchargeAmount  "1.25"
           :totalAmount      "13.75"
           :estimatedDays    4
           :billableWeightKg "3.2"
           :provider         "fake"}
          overrides)))

;; ---------------------------------------------------------------------------
;; Slice 2 -- test-only carrier-adapter stubs.
;;
;; parcelwise.carrier.fake/fake-carrier only supports :normal,
;; :malformed-rate, and :failure scenarios, and :failure always returns
;; {:status :error :error-type :upstream-error} -- there is no way to get a
;; :timeout (or any other specific) error-type, or an :ok/empty-rates
;; result, out of the production fake carrier. These reify the existing,
;; already-implemented parcelwise.carrier.protocol/CarrierAdapter protocol
;; directly so Slice 2 tests can exercise every branch the HTTP layer must
;; map (spec 9.3/9.4/9.5) without inventing any not-yet-existing production
;; API. Only depends on parcelwise.carrier.protocol (already implemented in
;; Slice 1), so -- like the rest of this file -- stays safely requireable
;; even before any Slice 2 production namespace exists.
;; ---------------------------------------------------------------------------

(defn error-carrier
  "A carrier adapter whose fetch-rates always returns a provider failure of
  the given `error-type` (e.g. :timeout, :upstream-error, :auth-failure,
  :rate-limited, :validation-error -- the full enum from
  parcelwise.specs/error-type)."
  [error-type]
  (reify protocol/CarrierAdapter
    (fetch-rates [_ _quote-request]
      {:status :error :error-type error-type})))

(defn empty-rates-carrier
  "A carrier adapter whose fetch-rates always succeeds but returns zero
  rates -- for exercising AC7 (successful provider response, no usable
  rates -> HTTP 200 with an empty quote list) without relying on filtering
  malformed rates down to nothing."
  []
  (reify protocol/CarrierAdapter
    (fetch-rates [_ _quote-request]
      {:status :ok :rates []})))

(defn never-called-carrier
  "A carrier adapter whose fetch-rates throws if it is ever invoked -- for
  proving AC2's 'the carrier adapter is not called' requirement for invalid
  requests. A test using this fixture that observes anything other than a
  clean 400 response (e.g. a 500, or an uncaught exception) demonstrates
  the carrier adapter was invoked when it should not have been."
  []
  (reify protocol/CarrierAdapter
    (fetch-rates [_ _quote-request]
      (throw (AssertionError.
               "carrier adapter must not be called for an invalid quote request")))))

;; ---------------------------------------------------------------------------
;; Slice 4 -- shared adapter-contract assertion (spec 13.4: "Define shared
;; behavioral expectations that both fake and Shippo adapters must satisfy
;; where practical"). A single, reusable helper rather than duplicating the
;; same "implements CarrierAdapter, returns a spec-valid carrier-result"
;; assertions per adapter test file. Only depends on
;; parcelwise.carrier.protocol and parcelwise.specs, both already
;; implemented in Slice 1, so it stays safely requireable from any adapter
;; test file regardless of which later-slice production namespaces exist.
;; ---------------------------------------------------------------------------

(defn assert-carrier-contract
  "Shared adapter-contract assertion (spec 13.4). Given an already
  constructed carrier adapter and a quote-request, asserts:
  1. the adapter implements parcelwise.carrier.protocol/CarrierAdapter;
  2. calling fetch-rates returns a value satisfying
     parcelwise.specs/carrier-result.
  This is the minimum shared behavioral contract every adapter (fake,
  Shippo, and any future adapter) must satisfy. Returns the fetch-rates
  result so callers can layer additional adapter-specific assertions on
  top without calling fetch-rates twice."
  [carrier request]
  (is (satisfies? protocol/CarrierAdapter carrier)
      "carrier must implement parcelwise.carrier.protocol/CarrierAdapter")
  (let [result (protocol/fetch-rates carrier request)]
    (is (s/valid? ::specs/carrier-result result)
        (str "fetch-rates result must satisfy ::specs/carrier-result: "
             (s/explain-str ::specs/carrier-result result)))
    result))

;; ---------------------------------------------------------------------------
;; Slice 4 fix round (CORR-S4-1) -- real-router-construction introspection
;; helpers.
;;
;; parcelwise.routes.services/service-routes returns plain reitit route
;; data (a nested vector, not a compiled router), so a route's :handler
;; function can be located by walking that data directly -- no need to
;; compile a full ring/reitit router or mock an HTTP request. The handler
;; itself, in turn, is an ordinary Clojure closure over whichever
;; carrier-adapter was actually constructed at that call site
;; (parcelwise.http.quotes-handler/handler's `[carrier-adapter provider]`
;; arity captures both directly) -- reflecting over its declared instance
;; fields lets a test observe *which* adapter object a real wiring call
;; site built, without invoking fetch-rates (so no network I/O ever
;; happens) and without asserting anything about which private function or
;; namespace performed the construction. This deliberately couples only to
;; the already-established, public parcelwise.http.quotes-handler/handler
;; closure contract, not to any private helper inside
;; parcelwise.routes.services.
;; ---------------------------------------------------------------------------

(defn find-quotes-post-handler
  "Given the return value of parcelwise.routes.services/service-routes,
  locates and returns the POST /api/quotes route's :handler function (the
  same reitit route-data shape parcelwise.routes.quotes-route-test's own
  swagger-doc helpers already walk), or nil if that route is missing
  entirely (e.g. because router construction crashed before returning)."
  [route-data]
  (some (fn [node]
          (when (and (vector? node) (= "/quotes" (first node)))
            (get-in (second node) [:post :handler])))
        (tree-seq vector? seq route-data)))

(defn closed-over-carrier-adapter
  "Given a compiled Clojure closure instance `f` (e.g. a ring handler fn
  returned by parcelwise.http.quotes-handler/handler), reflects over its
  declared instance fields to find and return the first captured value
  satisfying parcelwise.carrier.protocol/CarrierAdapter -- i.e. the actual
  carrier-adapter object the closure was constructed with. Returns nil if
  no such field is found."
  [f]
  (when (some? f)
    (some (fn [^java.lang.reflect.Field field]
            (.setAccessible field true)
            (let [v (.get field f)]
              (when (satisfies? protocol/CarrierAdapter v)
                v)))
          (.getDeclaredFields (class f)))))
