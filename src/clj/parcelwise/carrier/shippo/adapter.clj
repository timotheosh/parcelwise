(ns parcelwise.carrier.shippo.adapter
  "The Shippo test-mode CarrierAdapter implementation (spec section 8.2).

  [Action] -- wires the pure `parcelwise.carrier.shippo.mapping`
  calculations together with the real HTTP call in
  `parcelwise.carrier.shippo.client`. Reads its token/base-url/timeout
  configuration only from whatever is passed to `shippo-carrier` at
  construction time -- never reads `SHIPPO_API_TOKEN` or any other
  environment/config state directly, keeping this namespace decoupled
  from `mount`/env-reading (consistent with `client.clj`'s
  injectable-config design). Never logs the token or the full
  request/response bodies."
  (:require
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.carrier.shippo.client :as client]
    [parcelwise.carrier.shippo.mapping :as mapping]))

(defrecord ShippoCarrier [token base-url connect-timeout-ms request-timeout-ms]
  protocol/CarrierAdapter
  (fetch-rates [_ quote-request]
    (let [shippo-request (mapping/quote-request->shippo-request quote-request)
          outcome (client/fetch-shipment-rates
                    {:token               token
                     :base-url            base-url
                     :connect-timeout-ms  connect-timeout-ms
                     :request-timeout-ms  request-timeout-ms}
                    shippo-request)]
      (case (:outcome outcome)
        :ok    {:status :ok :rates (mapping/shippo-response->raw-rates (:body outcome))}
        :error {:status :error :error-type (:error-type outcome)}))))

(defn shippo-carrier
  "Constructs a `parcelwise.carrier.protocol/CarrierAdapter` backed by the
  real Shippo REST API. `config` is `{:keys [token base-url
  connect-timeout-ms request-timeout-ms]}` -- all explicit, injectable
  configuration (spec 8.2: 'Use explicit connection and request
  timeouts'), never hardcoded/defaulted here. This is what makes the
  adapter testable against a local loopback stub server instead of
  `https://api.goshippo.com`; real startup wiring is expected to supply
  `base-url \"https://api.goshippo.com\"` and concrete timeout constants
  explicitly, not rely on any default baked into this constructor."
  [{:keys [token base-url connect-timeout-ms request-timeout-ms]}]
  (->ShippoCarrier token base-url connect-timeout-ms request-timeout-ms))
