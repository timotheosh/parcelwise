(ns parcelwise.http.test-support
  "Shared HTTP-boundary test harness for Slice 2 (fake-provider API) tests.

  Deliberately kept OUT of parcelwise.test-support: this namespace has to
  :require parcelwise.http.quotes-handler, which does not exist yet, so
  loading this namespace currently throws (a clean, intended RED). Keeping
  it isolated here -- rather than folded into the main test-support file
  that every Slice 1 test file also requires -- means the not-yet-existing
  Slice 2 production namespace cannot collaterally break the already-GREEN
  Slice 1 test suite.

  `quotes-test-app` builds a minimal reitit-ring handler around
  `parcelwise.http.quotes-handler/handler`, mirroring the exact middleware
  stack already used for the real `/api` route table in
  `parcelwise.routes.services` (muuntaja content-negotiation/response/
  request middleware, `parcelwise.middleware.formats/instance`) but
  *without* reitit.ring.coercion -- per the approved implementation plan,
  runtime request validation for /api/quotes happens explicitly inside the
  handler (json-boundary + error-mapping + domain specs), not via reitit's
  generic spec-coercion middleware, so the required 400 field-error shape
  can be produced deterministically. This lets handler-level tests inject
  arbitrary test-only carrier-adapter stubs (see
  parcelwise.test-support/error-carrier and friends) directly, independent
  of whatever CARRIER_PROVIDER-driven mount wiring
  parcelwise.startup.carrier-config ends up using for the real app --
  parcelwise.routes.quotes-route-test covers that full end-to-end wiring
  separately, via (parcelwise.handler/app) itself."
  (:require
    [muuntaja.core :as m]
    [parcelwise.http.quotes-handler :as quotes-handler]
    [parcelwise.middleware.formats :as formats]
    [reitit.ring :as ring]
    [reitit.ring.middleware.muuntaja :as muuntaja]))

(defn quotes-test-app
  "Builds a minimal ring-handler exposing POST /api/quotes, wired to
  `parcelwise.http.quotes-handler/handler` constructed with the given
  `carrier-adapter`. Suitable for use with ring.mock.request the same way
  parcelwise.handler-test uses (parcelwise.handler/app)."
  [carrier-adapter]
  (ring/ring-handler
    (ring/router
      ["/api/quotes"
       {:post {:handler (quotes-handler/handler carrier-adapter)}}]
      {:data {:muuntaja formats/instance
              :middleware [muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware]}})))

(defn decode-body
  "Decodes a ring response's JSON body into a Clojure data structure,
  mirroring parcelwise.handler-test's parse-json helper."
  [response]
  (m/decode formats/instance "application/json" (:body response)))
