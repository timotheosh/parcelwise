(ns parcelwise.test-support
  "Shared, reusable fixture builders for Slice 1 (pure domain) tests.

  These builders return plain Clojure maps matching the shapes described in
  docs/specifications/parcelwise.md section 6 (Domain Model). Every builder
  accepts an optional map of overrides so individual tests can construct
  boundary/invalid variants without duplicating the full fixture literal.

  Reused across parcelwise.domain.calculations-test,
  parcelwise.domain.quote-engine-test, parcelwise.carrier.fake-test, and
  parcelwise.specs-test to avoid divergent, hand-rolled fixture data per
  namespace.")

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
