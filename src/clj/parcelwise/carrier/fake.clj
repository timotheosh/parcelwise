(ns parcelwise.carrier.fake
  "The default, deterministic carrier adapter for development and
  automated tests (spec section 8.1).

  [Calculation] -- deliberately implemented as a pure calculation rather
  than an action, even though it satisfies an I/O-shaped protocol: it
  makes no network calls, reads no mutable/environmental state, and
  always returns the same result for the same `scenario` + request. The
  `scenario` value is ordinary immutable data supplied at construction
  time, never global mutable state, so tests can construct as many
  independent fake carriers as they need."
  (:require
    [parcelwise.carrier.protocol :as protocol]))

(def ^:private normal-rates
  "A fixed, deterministic set of raw carrier rates (spec 8.1): at least
  three services with distinct prices and delivery estimates, in the
  pre-normalization/pre-filtering raw-rate shape (see
  parcelwise.domain.quote-engine)."
  [{:id              "fake-ground"
    :carrier         "Example Carrier"
    :service-code    "ground"
    :service-name    "Ground"
    :currency        "USD"
    :base-amount     12.50M
    :surcharge-amount 1.25M
    :total-amount    13.75M
    :estimated-days  4
    :available?      true}
   {:id              "fake-express"
    :carrier         "Example Carrier"
    :service-code    "express"
    :service-name    "Express"
    :currency        "USD"
    :base-amount     28.00M
    :surcharge-amount 2.50M
    :total-amount    30.50M
    :estimated-days  2
    :available?      true}
   {:id              "fake-overnight"
    :carrier         "Example Carrier"
    :service-code    "overnight"
    :service-name    "Overnight"
    :currency        "USD"
    :base-amount     52.00M
    :surcharge-amount 6.00M
    :total-amount    58.00M
    :estimated-days  1
    :available?      true}
   {:id              "fake-economy"
    :carrier         "Budget Carrier"
    :service-code    "economy"
    :service-name    "Economy"
    :currency        "USD"
    :base-amount      8.00M
    :surcharge-amount 0.50M
    :total-amount     8.50M
    :estimated-days  7
    :available?      true}])

(def ^:private malformed-rates
  "Normal rates plus one deliberately malformed rate (negative total
  price), so filtering can be exercised end to end (spec 8.1)."
  (conj normal-rates
        {:id              "fake-broken"
         :carrier         "Example Carrier"
         :service-code    "broken"
         :service-name    "Broken"
         :currency        "USD"
         :base-amount     10.00M
         :surcharge-amount 1.00M
         :total-amount    -5.00M
         :estimated-days  3
         :available?      true}))

(defrecord FakeCarrier [scenario]
  protocol/CarrierAdapter
  (fetch-rates [_ _quote-request]
    (case scenario
      :normal         {:status :ok :rates normal-rates}
      :malformed-rate {:status :ok :rates malformed-rates}
      :failure        {:status :error :error-type :upstream-error})))

(defn fake-carrier
  "Constructs a deterministic fake carrier adapter. `scenario` is data --
  one of :normal, :failure, or :malformed-rate -- controlling what
  fetch-rates returns; never global mutable state."
  [scenario]
  (->FakeCarrier scenario))
