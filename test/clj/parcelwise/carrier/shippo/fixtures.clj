(ns parcelwise.carrier.shippo.fixtures
  "Representative (synthesized, not literally recorded) Shippo test-API
  fixtures for Slice 4 (spec 13.4: 'The Shippo adapter may use recorded or
  representative provider fixtures for normalization tests. Remove secrets
  and personal address information from fixtures.').

  No fixture in this file contains a real API token, a real recorded
  address, or any other secret -- every value here is invented placeholder
  content shaped to match Shippo's publicly documented REST API request/
  response format (https://docs.goshippo.com/shippoapi/), since this
  environment has no live SHIPPO_API_TOKEN available to record an actual
  response from. See `.ai/test-plan.md`'s Slice 4 section, 'Assumed Shippo
  wire format,' for the full reasoning behind this shape.

  Mirrors the production namespace layout
  (`parcelwise.carrier.shippo.mapping`) per the task's explicit
  instruction to keep Shippo-specific fixtures under
  `test/clj/parcelwise/carrier/shippo/` rather than the shared, generic
  `parcelwise.test-support`.")

(defn shippo-rate
  "One valid Shippo rate object, already parsed into a Clojure map with
  keyword keys (as jsonista's default object mapper would produce),
  shaped per Shippo's documented rate-object fields."
  ([] (shippo-rate {}))
  ([overrides]
   (merge {:object_id      "rate_usps_priority_001"
           :amount         "12.50"
           :currency       "USD"
           :provider       "USPS"
           :servicelevel   {:name "Priority Mail" :token "usps_priority"}
           :estimated_days 2
           :messages       []}
          overrides)))

(defn shippo-response
  "A full, already-parsed Shippo shipment-rates response containing three
  distinct, valid rates -- mirrors spec 8.1's fake-adapter expectation of
  'at least three services with different prices and delivery estimates,'
  applied to the real Shippo wire shape so the same behavioral property is
  exercised for both adapters (spec 13.4: shared adapter contract)."
  ([] (shippo-response {}))
  ([overrides]
   (merge {:object_id "shipment_test_001"
           :status    "SUCCESS"
           :rates     [(shippo-rate)
                       (shippo-rate {:object_id      "rate_usps_ground_002"
                                     :amount         "8.20"
                                     :estimated_days 5
                                     :servicelevel   {:name  "Ground Advantage"
                                                       :token "usps_ground_advantage"}})
                       (shippo-rate {:object_id      "rate_fedex_overnight_003"
                                     :amount         "45.00"
                                     :provider       "FedEx"
                                     :estimated_days 1
                                     :servicelevel   {:name  "Standard Overnight"
                                                       :token "fedex_standard_overnight"}})]
           :messages  []}
          overrides)))

(defn shippo-response-with-malformed-rate
  "A Shippo response containing two valid rates and one defensively
  malformed entry (missing :amount) -- exercises spec 7.4/AC5's filtering
  guarantee ('one malformed provider rate must not invalidate every valid
  rate') against the real Shippo wire shape, the same pattern
  fake_test.clj already proves for the fake adapter. Real Shippo responses
  are not documented to ever omit :amount from a returned rate, but 7.4's
  rule is unconditional, not carrier-specific -- normalization must
  survive one defensively regardless."
  []
  (shippo-response {:rates [(shippo-rate)
                            (shippo-rate {:object_id "rate_broken_999"
                                          :amount    nil})
                            (shippo-rate {:object_id      "rate_usps_ground_002"
                                          :amount         "8.20"
                                          :estimated_days 5
                                          :servicelevel   {:name  "Ground Advantage"
                                                            :token "usps_ground_advantage"}})]}))

(def shippo-response-json
  "Raw JSON text for `shippo-response`'s default three-rate shape,
  hand-written to match it field-for-field. Used only by
  carrier.shippo.adapter-test's local-HTTP-stub integration test, so that
  test doesn't need to pull in a JSON-encoding library dependency of its
  own just to fabricate a response body for the stub server to serve."
  "{\"object_id\":\"shipment_test_001\",\"status\":\"SUCCESS\",\"rates\":[{\"object_id\":\"rate_usps_priority_001\",\"amount\":\"12.50\",\"currency\":\"USD\",\"provider\":\"USPS\",\"servicelevel\":{\"name\":\"Priority Mail\",\"token\":\"usps_priority\"},\"estimated_days\":2,\"messages\":[]},{\"object_id\":\"rate_usps_ground_002\",\"amount\":\"8.20\",\"currency\":\"USD\",\"provider\":\"USPS\",\"servicelevel\":{\"name\":\"Ground Advantage\",\"token\":\"usps_ground_advantage\"},\"estimated_days\":5,\"messages\":[]},{\"object_id\":\"rate_fedex_overnight_003\",\"amount\":\"45.00\",\"currency\":\"USD\",\"provider\":\"FedEx\",\"servicelevel\":{\"name\":\"Standard Overnight\",\"token\":\"fedex_standard_overnight\"},\"estimated_days\":1,\"messages\":[]}],\"messages\":[]}")
