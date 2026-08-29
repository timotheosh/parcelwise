(ns parcelwise.http.json-boundary
  "The explicit camelCase<->kebab-case key transform and BigDecimal<->
  decimal-string conversion for the /api/quotes HTTP boundary (spec
  sections 6.2, 6.4, 9.1, 13.2).

  [Calculation] -- every function here depends only on its explicit
  arguments and returns an explicit value. No I/O, no ring/HTTP machinery.
  The field-name vocabulary for this API is small and fixed (address,
  parcel, quote-request, normalized-quote), so the camelCase<->kebab-case
  mapping is spelled out explicitly per field rather than derived via a
  generic reversible algorithm -- a naive regex-based transform cannot
  correctly round-trip names like `street1`/`:street-1` in both
  directions (there is no uppercase-letter boundary before a trailing
  digit to detect on decode).")

;; ---------------------------------------------------------------------------
;; Decimal <-> string (spec 6.2, 6.4, 13.2)
;; ---------------------------------------------------------------------------

(defn encode-decimal
  "Formats a BigDecimal as a plain decimal string: no scientific notation,
  no Java/Clojure type suffix or class name (spec 6.4: 'Do not expose Java
  or Clojure implementation-specific number representations')."
  [^BigDecimal decimal]
  (.toPlainString decimal))

(defn decode-decimal-string
  "Parses a decimal string into a BigDecimal. Returns nil -- never throws
  -- for a malformed or non-string input (spec 6.2: 'Reject malformed
  numeric strings at the HTTP boundary'). Deliberately only answers 'is
  this parseable', not 'is this a valid business quantity' (e.g. positive,
  non-zero) -- that is the domain spec's separate concern, applied
  downstream."
  [s]
  (when (string? s)
    (try
      (bigdec s)
      (catch Exception _
        nil))))

;; ---------------------------------------------------------------------------
;; Request decoding: camelCase/decoded-JSON -> domain (kebab-case) shape
;; ---------------------------------------------------------------------------

(defn- decode-address
  [{:keys [name street1 street2 city region postalCode countryCode]}]
  {:name         name
   :street-1     street1
   :street-2     street2
   :city         city
   :region       region
   :postal-code  postalCode
   :country-code countryCode})

(defn- decode-parcel-decimal
  "Decodes one parcel decimal field. When the source value fails to parse
  as a decimal, the original (unparsed, non-numeric) value is passed
  through unchanged rather than coerced to nil -- so the domain spec's
  positive-number? predicate naturally rejects it downstream and produces
  a field-path error via error-mapping, instead of json-boundary
  duplicating that validation itself or throwing."
  [v]
  (or (decode-decimal-string v) v))

(defn- decode-parcel
  [{:keys [weightKg lengthCm widthCm heightCm]}]
  {:weight-kg (decode-parcel-decimal weightKg)
   :length-cm (decode-parcel-decimal lengthCm)
   :width-cm  (decode-parcel-decimal widthCm)
   :height-cm (decode-parcel-decimal heightCm)})

(defn decode-quote-request
  "Decodes a decoded-JSON quote-request body (camelCase keys, decimal
  fields as strings) into the domain quote-request shape (kebab-case keys,
  BigDecimal parcel fields where parseable)."
  [json-map]
  {:origin      (decode-address (:origin json-map))
   :destination (decode-address (:destination json-map))
   :parcel      (decode-parcel (:parcel json-map))})

;; ---------------------------------------------------------------------------
;; Response encoding: domain (kebab-case, BigDecimal) -> camelCase/JSON shape
;; ---------------------------------------------------------------------------

(defn- encode-quote
  [quote]
  (cond-> {:id               (:id quote)
           :carrier          (:carrier quote)
           :serviceCode      (:service-code quote)
           :serviceName      (:service-name quote)
           :currency         (:currency quote)
           :baseAmount       (encode-decimal (:base-amount quote))
           :surchargeAmount  (encode-decimal (:surcharge-amount quote))
           :totalAmount      (encode-decimal (:total-amount quote))
           :billableWeightKg (encode-decimal (:billable-weight-kg quote))
           :provider         (name (:provider quote))}
    (contains? quote :estimated-days) (assoc :estimatedDays (:estimated-days quote))))

(defn encode-quote-response
  "Encodes a seq of normalized quotes into the response body shape (spec
  9.1): `{:quotes [<camelCase-map-with-string-decimal-fields>...]}`. An
  empty input encodes as an empty array, not nil/absent (AC7)."
  [quotes]
  {:quotes (mapv encode-quote quotes)})
