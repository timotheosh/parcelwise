(ns parcelwise.carrier.shippo.mapping
  "Pure request/response mapping and failure classification for the Shippo
  test-mode carrier adapter (spec section 8.2).

  [Calculation] -- every function here depends only on its explicit
  arguments, returns an explicit value, and performs no I/O, mutation,
  clock access, or logging. `parcelwise.carrier.shippo.adapter` [Action]
  is the only caller that performs any network I/O; this namespace never
  does.

  Assumed Shippo wire format (units, field names, judgment calls around
  base/surcharge splitting and :available?) is documented in
  `.ai/test-plan.md`'s Slice 4 'Assumed Shippo wire format' section and is
  reproduced in the docstrings below where it drives a specific mapping
  decision."
  (:import
    [java.math RoundingMode]))

;; ---------------------------------------------------------------------------
;; Unit conversion (spec 8.2: "Convert kilograms and centimeters into units
;; required by Shippo"). Assumed wire units: pounds for mass, inches for
;; distance. Named constants per spec 7.1's "named domain constant, not a
;; bare literal" discipline, applied here to this slice's own conversion
;; factors.
;; ---------------------------------------------------------------------------

(def kg-to-lb-factor
  "Kilograms-to-pounds conversion factor, full precision."
  2.20462262185M)

(def cm-to-in-factor
  "Centimeters-to-inches conversion factor, full precision."
  0.393700787402M)

(defn kg->lb
  "Converts kilograms to pounds at full BigDecimal precision. Does not
  round -- rounding to a wire-safe decimal string happens only in
  `decimal->wire-string`, at the request-boundary formatting step (mirrors
  Slice 1's dimensional-weight-kg/billable-weight-kg 'do not round
  prematurely' discipline)."
  [kg]
  (* kg kg-to-lb-factor))

(defn cm->in
  "Converts centimeters to inches at full BigDecimal precision. Does not
  round; see `kg->lb`."
  [cm]
  (* cm cm-to-in-factor))

(defn decimal->wire-string
  "Formats a BigDecimal as a 2-decimal-place wire string, rounding
  half-up. The only place rounding happens in this namespace -- matches
  how the application's own HTTP boundary already encodes decimal values
  as strings (spec 6.4/9.1)."
  [value]
  (.toPlainString (.setScale (bigdec value) 2 RoundingMode/HALF_UP)))

;; ---------------------------------------------------------------------------
;; Request mapping (spec 8.2: "Map application addresses and parcel data to
;; the Shippo request"). Field names mirror Shippo's own documented
;; snake_case wire shape verbatim -- this is a private wire adapter to a
;; third-party API, not a domain-facing boundary, so no camelCase/kebab-case
;; translation layer is introduced here (unlike http/json_boundary.clj,
;; which exists because THIS application's own HTTP contract is
;; application-owned and stable).
;; ---------------------------------------------------------------------------

(defn- address->shippo-address
  "Maps one application address into Shippo's documented address-object
  field names. `street-2` is nilable on both sides (spec 6.1) and passed
  through as-is."
  [{:keys [name street-1 street-2 city region postal-code country-code]}]
  {:name    name
   :street1 street-1
   :street2 street-2
   :city    city
   :state   region
   :zip     postal-code
   :country country-code})

(defn- parcel->shippo-parcel
  "Maps one application parcel into Shippo's documented single-parcel wire
  shape: converted, wire-formatted (2-decimal-string) weight/dimensions,
  plus the lb/in unit markers (spec 8.2's assumed wire units)."
  [{:keys [weight-kg length-cm width-cm height-cm]}]
  {:length        (decimal->wire-string (cm->in length-cm))
   :width         (decimal->wire-string (cm->in width-cm))
   :height        (decimal->wire-string (cm->in height-cm))
   :distance_unit "in"
   :weight        (decimal->wire-string (kg->lb weight-kg))
   :mass_unit     "lb"})

(defn quote-request->shippo-request
  "Maps a full application quote-request into the assumed Shippo request
  wire shape: `address_from`/`address_to`/`parcels` (a single-element
  vector -- spec 4 explicitly puts multiple parcels out of scope) plus
  `:async false`, which deliberately requests rate retrieval only, never a
  label purchase or transaction (spec 8.2: 'Avoid purchasing labels or
  creating transactions beyond what is required to retrieve rates')."
  [{:keys [origin destination parcel]}]
  {:address_from (address->shippo-address origin)
   :address_to   (address->shippo-address destination)
   :parcels      [(parcel->shippo-parcel parcel)]
   :async        false})

;; ---------------------------------------------------------------------------
;; Response normalization (spec 8.2: "Normalize Shippo rates into the
;; application quote model") -- Shippo rate -> the same raw-rate shape
;; parcelwise.domain.quote-engine/filter-valid-rates and normalize-rate
;; already consume (spec 13.4).
;; ---------------------------------------------------------------------------

(defn- parse-wire-decimal
  "Parses a Shippo wire decimal value (a quoted string, per Shippo's
  documented shape) into a BigDecimal, or `nil` when it is missing or not
  parseable -- never throws. A `nil` result flows into
  `quote-engine/filter-valid-rates` as an invalid `:total-amount`, which
  is exactly how a defensively malformed Shippo rate gets excluded
  without invalidating the rest of the response (spec 7.4/AC5)."
  [value]
  (when (some? value)
    (try
      (bigdec value)
      (catch Exception _ nil))))

(defn- shippo-rate->raw-rate
  "Maps one already-parsed Shippo rate object into the raw-rate shape
  (spec 6.4-adjacent, pre-normalization): `object_id` -> `:id`, `provider`
  -> `:carrier`, `servicelevel.token`/`servicelevel.name` ->
  `:service-code`/`:service-name`, `amount` -> both `:base-amount` and
  `:total-amount` (Shippo does not split price into base+surcharge; see
  namespace docstring and `.ai/test-plan.md` for the documented judgment
  call), `estimated_days` -> `:estimated-days`. `:surcharge-amount` is
  always `0M` and `:available?` is always `true` (Shippo's real API does
  not document a per-rate unavailable flag; unavailable services are
  simply absent from the response)."
  [{:keys [object_id amount currency provider servicelevel estimated_days]}]
  (let [parsed-amount (parse-wire-decimal amount)]
    {:id               object_id
     :carrier          provider
     :service-code     (:token servicelevel)
     :service-name     (:name servicelevel)
     :currency         currency
     :base-amount      parsed-amount
     :surcharge-amount 0M
     :total-amount     parsed-amount
     :estimated-days   estimated_days
     :available?       true}))

(defn shippo-response->raw-rates
  "Maps an already-parsed Shippo shipment-rates response (a Clojure map
  with keyword keys) into a vector of raw rates, one per Shippo rate
  object, in the exact shape
  `parcelwise.domain.quote-engine/filter-valid-rates`/`normalize-rate`
  already consume. Does not itself filter or validate -- a defensively
  malformed rate (e.g. missing `:amount`) still produces one raw-rate
  entry, which `filter-valid-rates` is responsible for excluding (spec
  7.4/AC5: one malformed rate must not invalidate the others)."
  [{:keys [rates]}]
  (mapv shippo-rate->raw-rate rates))

;; ---------------------------------------------------------------------------
;; Failure classification (spec 8.2: "Map Shippo authentication, validation,
;; timeout, rate-limit, and upstream failures into stable application
;; errors"). Reuses parcelwise.specs/error-type's existing enum exactly --
;; no new error-type value is invented here.
;; ---------------------------------------------------------------------------

(defn classify-failure
  "Classifies an HTTP-outcome/Shippo-error descriptor into one of
  `parcelwise.specs/error-type`'s existing values. `{:timeout? true}` ->
  `:timeout`. Otherwise, classifies by `:status`: 401/403 ->
  `:auth-failure`, 400/422 -> `:validation-error`, 429 -> `:rate-limited`,
  every other status (including unrecognized/unexpected ones) ->
  `:upstream-error` -- a safe, always-classified default, never throws
  and never exposes the raw status past this boundary."
  [{:keys [status timeout?]}]
  (cond
    timeout?                     :timeout
    (contains? #{401 403} status) :auth-failure
    (contains? #{400 422} status) :validation-error
    (= 429 status)                :rate-limited
    :else                         :upstream-error))
