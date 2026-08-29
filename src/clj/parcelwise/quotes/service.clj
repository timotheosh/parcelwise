(ns parcelwise.quotes.service
  "The orchestrator between the HTTP boundary and the pure Slice-1 domain
  calculations: calls the injected carrier adapter, and on success runs
  the filter-valid-rates -> normalize-rate -> order-quotes pipeline; on
  carrier failure, passes the carrier's own error info through unchanged
  for the HTTP layer to map (spec 9.3/9.4).

  [Action] -- thin wrapper. It calls `fetch-rates` on the injected
  `carrier-adapter` (an external-system boundary, per spec section 8),
  but every business decision -- computing billable weight, filtering,
  normalizing, ordering -- is delegated to the already-implemented,
  already-reviewed pure calculations in parcelwise.domain.calculations and
  parcelwise.domain.quote-engine."
  (:require
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.domain.calculations :as calculations]
    [parcelwise.domain.quote-engine :as quote-engine]))

(defn get-quotes
  "Fetches rates from `carrier-adapter` for `quote-request`, stamping
  successful results with `provider` (spec 6.4's :provider field --
  explicit here because CarrierAdapter has no method exposing which
  provider keyword normalized quotes should carry).

  Returns `{:status :ok :quotes [<normalized-quote>...]}` on carrier
  success (after running the Slice 1 filter/normalize/order pipeline), or
  `{:status :error :error-type <kind>}` (the carrier's own error-type,
  unchanged) on carrier failure."
  [carrier-adapter provider quote-request]
  (let [result (protocol/fetch-rates carrier-adapter quote-request)]
    (case (:status result)
      :ok
      (let [{:keys [weight-kg length-cm width-cm height-cm]} (:parcel quote-request)
            dimensional-weight-kg (calculations/dimensional-weight-kg length-cm width-cm height-cm)
            billable-weight-kg (calculations/billable-weight-kg weight-kg dimensional-weight-kg)
            context {:billable-weight-kg billable-weight-kg :provider provider}
            quotes (->> (:rates result)
                        (quote-engine/filter-valid-rates)
                        (map #(quote-engine/normalize-rate % context))
                        (quote-engine/order-quotes))]
        {:status :ok :quotes quotes})

      :error
      {:status :error :error-type (:error-type result)})))
