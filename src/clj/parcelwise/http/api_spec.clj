(ns parcelwise.http.api-spec
  "Hand-authored Swagger 2.0 wire-format schemas documenting POST
  /api/quotes (spec section 9.6). Attached to the route via reitit's
  `:swagger {:parameters ... :responses ...}` route-data mechanism,
  independent of runtime request validation (parcelwise.http.quotes-handler
  validates explicitly; this route has no :coercion, so reitit-swagger
  merges this data in verbatim -- see the route wiring in
  parcelwise.routes.services).

  [Data] -- passive facts describing the application-owned wire contract.
  No behavior lives here."
  )

;; ---------------------------------------------------------------------------
;; Request schema (spec 6.1, 6.2, 9.1)
;; ---------------------------------------------------------------------------

(def ^:private address-schema
  {:type "object"
   :required ["name" "street1" "city" "region" "postalCode" "countryCode"]
   :properties
   {:name        {:type "string" :example "Jane Example"}
    :street1     {:type "string" :example "123 Market Street"}
    :street2     {:type "string" :x-nullable true :example nil}
    :city        {:type "string" :example "Philadelphia"}
    :region      {:type "string" :example "PA"}
    :postalCode  {:type "string" :example "19103"}
    :countryCode {:type "string"
                   :pattern "^[A-Z]{2}$"
                   :example "US"
                   :description "Two-character uppercase ISO-style country code."}}})

(def ^:private parcel-schema
  {:type "object"
   :required ["weightKg" "lengthCm" "widthCm" "heightCm"]
   :properties
   {:weightKg {:type "string" :example "3.2" :description "Kilograms, as a decimal string; must be > 0."}
    :lengthCm {:type "string" :example "30" :description "Centimeters, as a decimal string; must be > 0."}
    :widthCm  {:type "string" :example "20" :description "Centimeters, as a decimal string; must be > 0."}
    :heightCm {:type "string" :example "15" :description "Centimeters, as a decimal string; must be > 0."}}})

(def ^:private quote-request-schema
  {:type "object"
   :required ["origin" "destination" "parcel"]
   :properties
   {:origin      address-schema
    :destination address-schema
    :parcel      parcel-schema}})

;; ---------------------------------------------------------------------------
;; Successful response schema (spec 6.4, 9.1)
;; ---------------------------------------------------------------------------

(def ^:private normalized-quote-schema
  {:type "object"
   :required ["id" "carrier" "serviceCode" "serviceName" "currency"
              "baseAmount" "surchargeAmount" "totalAmount"
              "billableWeightKg" "provider"]
   :properties
   {:id               {:type "string" :example "fake-ground"}
    :carrier          {:type "string" :example "Example Carrier"}
    :serviceCode      {:type "string" :example "ground"}
    :serviceName      {:type "string" :example "Ground"}
    :currency         {:type "string" :example "USD"}
    :baseAmount       {:type "string" :example "12.50"}
    :surchargeAmount  {:type "string" :example "1.25"}
    :totalAmount      {:type "string" :example "13.75"}
    :estimatedDays    {:type "integer" :x-nullable true :example 4
                        :description "May be absent when the provider does not supply an estimate."}
    :billableWeightKg {:type "string" :example "3.2"}
    :provider         {:type "string" :example "fake"}}})

(def ^:private quotes-response-schema
  {:type "object"
   :required ["quotes"]
   :properties
   {:quotes {:type "array" :items normalized-quote-schema}}
   :example {:quotes [{:id "fake-ground" :carrier "Example Carrier"
                        :serviceCode "ground" :serviceName "Ground"
                        :currency "USD" :baseAmount "12.50"
                        :surchargeAmount "1.25" :totalAmount "13.75"
                        :estimatedDays 4 :billableWeightKg "3.2"
                        :provider "fake"}]}})

;; ---------------------------------------------------------------------------
;; Error response schemas (spec 9.2, 9.3, 9.4)
;; ---------------------------------------------------------------------------

(defn- error-response-schema
  [extra-required extra-properties]
  {:type "object"
   :required ["error"]
   :properties
   {:error {:type "object"
            :required (into ["code" "message"] extra-required)
            :properties (merge {:code {:type "string"} :message {:type "string"}}
                                extra-properties)}}})

(def ^:private validation-error-schema
  (-> (error-response-schema
        ["fields"]
        {:fields {:type "object"
                  :additionalProperties {:type "array" :items {:type "string"}}
                  :example {:parcel.weightKg ["must be greater than zero"]}}})
      (assoc-in [:properties :error :properties :code :example] "invalid-request")
      (assoc-in [:properties :error :properties :message :example] "The quote request is invalid.")))

(def ^:private carrier-unavailable-schema
  (-> (error-response-schema [] {})
      (assoc-in [:properties :error :properties :code :example] "carrier-unavailable")
      (assoc-in [:properties :error :properties :message :example] "Shipping rates are temporarily unavailable.")))

(def ^:private carrier-timeout-schema
  (-> (error-response-schema [] {})
      (assoc-in [:properties :error :properties :code :example] "carrier-timeout")
      (assoc-in [:properties :error :properties :message :example] "The shipping-rate request timed out.")))

;; ---------------------------------------------------------------------------
;; Route-data :swagger map for POST /api/quotes
;; ---------------------------------------------------------------------------

(def quotes-route-data
  "Route-data to merge into the POST /api/quotes route: top-level
  `:summary`/`:description` (reitit-swagger reads these directly off route
  data), plus the `:swagger {:parameters ... :responses ...}` sub-map that
  reitit-swagger merges verbatim into /api/swagger.json, independent of
  this route's runtime request validation (spec 9.6). This route has no
  `:coercion`, so `coercion/get-apidocs` contributes nothing and this data
  fully determines the documented request/response schemas."
  {:summary "Request shipping quotes"
   :description "Accepts an origin address, destination address, and parcel description, and returns normalized, ordered shipping quotes from the configured carrier provider."
   :swagger
   {:parameters [{:in "body"
                  :name "quoteRequest"
                  :description "The origin, destination, and parcel to request quotes for."
                  :required true
                  :schema quote-request-schema}]
    :responses {200 {:description "Quotes were retrieved successfully (possibly an empty list, spec 9.5/AC7)."
                      :schema quotes-response-schema}
                400 {:description "The request failed boundary validation (spec 9.2)."
                     :schema validation-error-schema}
                502 {:description "The carrier provider is unavailable (spec 9.3)."
                     :schema carrier-unavailable-schema}
                504 {:description "The carrier provider request timed out (spec 9.4)."
                     :schema carrier-timeout-schema}}}})
