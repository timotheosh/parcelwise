(ns parcelwise.http.quotes-handler
  "The POST /api/quotes ring handler: parses the already-muuntaja-decoded
  request body, validates it against the domain spec, and either returns a
  stable 400 field-error response or delegates to
  parcelwise.quotes.service and maps its result onto the stable HTTP
  contract (spec sections 9, 11, 12, 13.3).

  [Action] -- thin wrapper around a ring request/response. Runtime request
  validation stays explicit here (json-boundary + error-mapping + the
  domain spec) rather than being delegated to reitit's generic
  spec-coercion middleware, so the required 400 field-error envelope shape
  can be produced deterministically -- see the route wiring in
  parcelwise.routes.services, which turns coercion off for this specific
  route."
  (:require
    [clojure.spec.alpha :as s]
    [parcelwise.http.error-mapping :as error-mapping]
    [parcelwise.http.json-boundary :as json-boundary]
    [parcelwise.quotes.service :as service]
    [parcelwise.specs :as specs]
    [ring.util.http-response :as resp]))

(defn- invalid-request-response
  [explain-data]
  (resp/bad-request
    {:error {:code    "invalid-request"
             :message "The quote request is invalid."
             :fields  (error-mapping/field-errors explain-data)}}))

(defn- carrier-error-response
  "Maps a carrier-adapter error-type onto the stable, secret-free HTTP
  error envelopes required by spec 9.3/9.4. Never exposes the raw,
  granular carrier error-type, provider details, or exception information
  (spec 9.3, 12) -- :timeout is the only error-type distinguished at the
  HTTP boundary (504); every other error-type collapses to the same 502
  'carrier-unavailable' response."
  [error-type]
  (if (= :timeout error-type)
    (resp/gateway-timeout
      {:error {:code    "carrier-timeout"
               :message "The shipping-rate request timed out."}})
    (resp/bad-gateway
      {:error {:code    "carrier-unavailable"
               :message "Shipping rates are temporarily unavailable."}})))

(defn handler
  "Constructs a ring handler for POST /api/quotes, bound to the given
  `carrier-adapter` (and, optionally, the `provider` keyword normalized
  quotes should be stamped with -- defaults to :fake, this slice's only
  wired provider)."
  ([carrier-adapter] (handler carrier-adapter :fake))
  ([carrier-adapter provider]
   (fn quotes-handler [request]
     (let [domain-request (json-boundary/decode-quote-request (:body-params request))
           explain-data (s/explain-data ::specs/quote-request domain-request)]
       (if explain-data
         (invalid-request-response explain-data)
         (let [result (service/get-quotes carrier-adapter provider domain-request)]
           (case (:status result)
             :ok (resp/ok (json-boundary/encode-quote-response (:quotes result)))
             :error (carrier-error-response (:error-type result)))))))))
