(ns parcelwise.carrier.protocol
  "The narrow carrier-adapter abstraction described in spec section 8.

  The protocol itself is a structural interface declaration, not an
  ACD-classifiable unit on its own -- individual adapters implementing it
  (fake, Shippo) are classified where they're defined.")

(defprotocol CarrierAdapter
  "Retrieves shipping rates for a quote request. Implementations must
  return a `parcelwise.specs/carrier-result`-shaped map distinguishing
  successful provider data (`{:status :ok :rates [...]}`) from provider
  failure (`{:status :error :error-type <kind>}`) without leaking
  provider-specific data into the domain layer."
  (fetch-rates [this quote-request]))
