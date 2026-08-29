(ns parcelwise.routes.quotes-route-test
  "Full-stack route tests for POST /api/quotes, exercised through the real
  application (parcelwise.handler/app), the real route wiring in
  parcelwise.routes.services, and the real, env-driven
  parcelwise.startup.carrier-config wiring -- unlike
  parcelwise.http.quotes-handler-test, which injects test-only carrier
  stubs directly and bypasses route/mount wiring entirely.

  Covers what can only be verified with the full stack actually wired up:
  that /api/quotes exists and is reachable at all, the default
  CARRIER_PROVIDER=fake path (spec section 8.2's documented default) with
  the real FakeCarrier :normal scenario, OpenAPI documentation accuracy
  (spec 9.6), and the request-body size limit (spec 12), which is applied
  by middleware wired into the real route table.

  Follows exactly the same ring-mock + muuntaja-decode pattern as the
  pre-existing parcelwise.handler-test (reused, not reinvented)."
  (:require
    [clojure.test :refer :all]
    [mount.core :as mount]
    [muuntaja.core :as m]
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.config :as config]
    [parcelwise.handler :refer [app]]
    [parcelwise.middleware.formats :as formats]
    [parcelwise.routes.services :as services]
    [parcelwise.startup.carrier-config :as carrier-config]
    [parcelwise.test-support :as ts]
    [ring.mock.request :refer :all]))

(defn- parse-json [body]
  (m/decode formats/instance "application/json" body))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'parcelwise.config/env
                 #'parcelwise.handler/app-routes)
    (f)))

;; ---------------------------------------------------------------------------
;; AC1, end to end through the real route table and default (fake) carrier
;; ---------------------------------------------------------------------------

(deftest quotes-route-exists-and-returns-200-for-a-valid-request-test
  (let [response ((app) (-> (request :post "/api/quotes")
                            (json-body (ts/json-quote-request))))]
    (testing "the route exists and is wired up (not a 404)"
      (is (= 200 (:status response))))
    (when (= 200 (:status response))
      (testing "Content-Type: application/json"
        (is (re-find #"application/json" (get-in response [:headers "Content-Type"] ""))))
      (testing "the default CARRIER_PROVIDER=fake path returns >= 3 quotes (AC1), matching spec section 8.2's documented default"
        (is (>= (count (:quotes (parse-json (:body response)))) 3))))))

;; ---------------------------------------------------------------------------
;; OpenAPI accuracy (spec 9.6)
;; ---------------------------------------------------------------------------

(defn- swagger-doc []
  (parse-json (:body ((app) (request :get "/api/swagger.json")))))

(defn- quotes-post-operation []
  (get-in (swagger-doc) [:paths (keyword "/api/quotes") :post]))

(defn- body-parameter [operation]
  (->> (:parameters operation)
       (filter #(= "body" (:in %)))
       first))

(deftest swagger-json-documents-the-quotes-path-test
  (testing "/api/quotes appears in the generated OpenAPI/Swagger document at all"
    (is (some? (quotes-post-operation)))))

(deftest swagger-json-request-body-schema-requires-origin-destination-parcel-test
  (let [schema (:schema (body-parameter (quotes-post-operation)))]
    (is (some? schema) "a body parameter with a :schema must be documented")
    (is (= #{"origin" "destination" "parcel"} (set (:required schema))))))

(deftest swagger-json-decimal-fields-documented-as-strings-not-numbers-test
  (let [schema (:schema (body-parameter (quotes-post-operation)))
        parcel-props (get-in schema [:properties :parcel :properties])]
    (doseq [field [:weightKg :lengthCm :widthCm :heightCm]]
      (is (= "string" (get-in parcel-props [field :type]))
          (str field " must be documented as a JSON Schema string, not number (spec 9.6, and the plan's explicit instruction)")))))

(deftest swagger-json-200-response-documents-quotes-array-with-required-fields-test
  (let [responses (:responses (quotes-post-operation))
        response-200 (:200 responses)
        quote-item-props (get-in response-200 [:schema :properties :quotes :items :properties])]
    (is (= "array" (get-in response-200 [:schema :properties :quotes :type])))
    (doseq [field [:id :carrier :serviceCode :serviceName :currency
                   :baseAmount :surchargeAmount :totalAmount
                   :billableWeightKg :provider]]
      (is (contains? quote-item-props field) (str field " missing from documented quote-item schema")))
    (testing "decimal per-quote fields documented as strings, not numbers"
      (doseq [field [:baseAmount :surchargeAmount :totalAmount :billableWeightKg]]
        (is (= "string" (get-in quote-item-props [field :type])))))))

(deftest swagger-json-error-responses-documented-test
  (let [responses (:responses (quotes-post-operation))]
    (testing "400 invalid-request"
      (let [schema (get-in responses [:400 :schema])]
        (is (some? (get-in schema [:properties :error :properties :code])))
        (is (some? (get-in schema [:properties :error :properties :message])))
        (is (some? (get-in schema [:properties :error :properties :fields])))))
    (testing "502 carrier-unavailable"
      (let [schema (get-in responses [:502 :schema])]
        (is (some? (get-in schema [:properties :error :properties :code])))
        (is (some? (get-in schema [:properties :error :properties :message])))))
    (testing "504 carrier-timeout"
      (let [schema (get-in responses [:504 :schema])]
        (is (some? (get-in schema [:properties :error :properties :code])))
        (is (some? (get-in schema [:properties :error :properties :message])))))))

;; ---------------------------------------------------------------------------
;; Request-body size limit (spec 12) -- full round-trip sanity check.
;;
;; The core "actual bytes read, not just Content-Length" behavior is
;; independently and more precisely tested at the middleware-function level
;; in parcelwise.middleware.body-size-limit-test, per the approved plan's
;; own note that ring-mock may not easily simulate a body that lies about
;; its own length. This test only checks that an obviously, grossly
;; oversized body is rejected by the fully-wired route, as an end-to-end
;; sanity check that the middleware is actually mounted on this route.
;; ---------------------------------------------------------------------------

(deftest oversized-request-body-is-rejected-test
  (let [oversized-body (apply str (repeat (* 3 1024 1024) \x)) ; 3 MB, deliberately not valid JSON
        response ((app) (-> (request :post "/api/quotes")
                            (body oversized-body)
                            (content-type "application/json")))]
    (is (= 413 (:status response)))))

;; ---------------------------------------------------------------------------
;; CORR-S4-1 fix round -- CARRIER_PROVIDER=shippo must actually be usable by
;; the real running application, not just by decide-provider/shippo-carrier
;; in isolation (correctness-reviewer's reproduction: `CARRIER_PROVIDER=shippo
;; lein run`, with and without SHIPPO_API_TOKEN, both crash the real app with
;; a raw `IllegalArgumentException: No matching clause` instead of either
;; using a genuine Shippo adapter or decide-provider's documented secret-free
;; fail-fast message).
;;
;; These call parcelwise.routes.services/service-routes directly (the exact,
;; real function parcelwise.handler/app-routes invokes to build the live
;; route table) with parcelwise.config/env temporarily rebound via
;; with-redefs, rather than mounting the whole app with real OS environment
;; variables -- this exercises the identical code path a real `lein run`
;; would, without needing to shell out to a subprocess or mutate real
;; process environment variables. No network call happens merely by calling
;; service-routes: it only *constructs* route data (closures), it does not
;; invoke any handler, so this stays consistent with this codebase's
;; established "no live network calls in tests" policy.
;; ---------------------------------------------------------------------------

(deftest shippo-provider-without-token-fails-fast-at-router-construction-test
  (testing "spec 8.2: CARRIER_PROVIDER=shippo without SHIPPO_API_TOKEN must fail real router construction with decide-provider's own secret-free message -- not the raw, unrelated `IllegalArgumentException: No matching clause` the correctness reviewer reproduced against the real running app (CORR-S4-1)"
    (with-redefs [config/env {:carrier-provider "shippo"}]
      (let [outcome (try {:routes (services/service-routes)}
                          (catch Throwable t {:thrown t}))]
        (is (contains? outcome :thrown)
            "selecting shippo without a token must fail router construction, not silently succeed and serve requests")
        (when-let [thrown (:thrown outcome)]
          (let [message (or (.getMessage thrown) "")
                expected-message @#'carrier-config/missing-token-message]
            (testing "must carry decide-provider's existing, already-tested, secret-free fail-fast message (reused, not duplicated, from parcelwise.startup.carrier-config)"
              (is (re-find (re-pattern (java.util.regex.Pattern/quote expected-message)) message)))
            (testing "must NOT be the raw Clojure dispatch-failure exception CORR-S4-1 reproduced against the real app"
              (is (not (re-find #"No matching clause" message))
                  "a bare `No matching clause` message means decide-provider's :error decision is still not wired to an actual startup-time failure"))))))))

(deftest shippo-provider-with-valid-token-is-genuinely-wired-into-router-construction-test
  (testing "spec 8.2/16: CARRIER_PROVIDER=shippo with SHIPPO_API_TOKEN present must not crash real router construction, and the constructed /api/quotes handler must genuinely be backed by the Shippo carrier adapter (carrying the configured token) -- not silently falling back to the fake carrier or any other placeholder (CORR-S4-1)"
    (with-redefs [config/env {:carrier-provider "shippo" :shippo-api-token "test_dummy_token_123"}]
      (let [route-data (services/service-routes)
            handler-fn (ts/find-quotes-post-handler route-data)]
        (is (some? handler-fn)
            "the /api/quotes POST route must still be constructed for a valid shippo selection")
        (when (some? handler-fn)
          (let [adapter (ts/closed-over-carrier-adapter handler-fn)]
            (is (some? adapter)
                "a carrier-adapter object must be discoverable in the constructed handler's closure")
            (when (some? adapter)
              (is (satisfies? protocol/CarrierAdapter adapter))
              (is (= "test_dummy_token_123" (:token adapter))
                  "the constructed adapter must carry the actual configured Shippo token -- a FakeCarrier (or any other placeholder that ignores the token) would not have a :token field at all"))))))))
