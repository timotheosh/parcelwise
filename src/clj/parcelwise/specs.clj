(ns parcelwise.specs
  "clojure.spec definitions for the boundary data described in spec
  section 6 (Domain Model) and section 8 (Carrier Boundary).

  [Data] -- these specs describe passive facts (addresses, parcels,
  quote requests, normalized quotes, carrier-adapter results); the spec
  predicates themselves are pure calculations with no I/O or mutation.
  Operates on already-typed Clojure data (numbers/BigDecimals), not raw
  HTTP/JSON strings -- HTTP-boundary coercion is Slice 2's concern."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Shared predicates
;; ---------------------------------------------------------------------------

(defn- non-blank-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- finite-number?
  [v]
  (and (number? v)
       (not (and (double? v) (or (Double/isNaN v) (Double/isInfinite v))))))

(defn- positive-number?
  [v]
  (and (finite-number? v) (pos? v)))

;; ---------------------------------------------------------------------------
;; 6.1 Address
;; ---------------------------------------------------------------------------

(s/def ::name non-blank-string?)
(s/def ::street-1 non-blank-string?)
(s/def ::street-2 (s/nilable string?))
(s/def ::city non-blank-string?)
(s/def ::region non-blank-string?)
(s/def ::postal-code non-blank-string?)
(s/def ::country-code (s/and string? #(re-matches #"[A-Z]{2}" %)))

(s/def ::address
  (s/keys :req-un [::name ::street-1 ::city ::region ::postal-code ::country-code]
          :opt-un [::street-2]))

;; ---------------------------------------------------------------------------
;; 6.2 Parcel
;; ---------------------------------------------------------------------------

(s/def ::weight-kg positive-number?)
(s/def ::length-cm positive-number?)
(s/def ::width-cm positive-number?)
(s/def ::height-cm positive-number?)

(s/def ::parcel
  (s/keys :req-un [::weight-kg ::length-cm ::width-cm ::height-cm]))

;; ---------------------------------------------------------------------------
;; 6.3 Quote request
;; ---------------------------------------------------------------------------

(s/def ::origin ::address)
(s/def ::destination ::address)

(s/def ::quote-request
  (s/keys :req-un [::origin ::destination ::parcel]))

;; ---------------------------------------------------------------------------
;; 6.4 Normalized quote
;; ---------------------------------------------------------------------------

(s/def ::id non-blank-string?)
(s/def ::carrier non-blank-string?)
(s/def ::service-code non-blank-string?)
(s/def ::service-name non-blank-string?)
(s/def ::currency non-blank-string?)
(s/def ::base-amount number?)
(s/def ::surcharge-amount number?)
(s/def ::total-amount finite-number?)
(s/def ::estimated-days (s/nilable int?))
(s/def ::billable-weight-kg positive-number?)
(s/def ::provider keyword?)

(s/def ::normalized-quote
  (s/keys :req-un [::id ::carrier ::service-code ::service-name ::currency
                    ::base-amount ::surcharge-amount ::total-amount
                    ::billable-weight-kg ::provider]
          :opt-un [::estimated-days]))

;; ---------------------------------------------------------------------------
;; 8. Carrier-adapter result
;; ---------------------------------------------------------------------------

(s/def ::rates (s/coll-of map?))
(s/def ::error-type #{:timeout :auth-failure :rate-limited :validation-error :upstream-error})

(defmulti ^:private carrier-result-status :status)
(defmethod carrier-result-status :ok [_] (s/keys :req-un [::rates]))
(defmethod carrier-result-status :error [_] (s/keys :req-un [::error-type]))
(defmethod carrier-result-status :default [_] (s/and any? (constantly false)))

(s/def ::status #{:ok :error})

(s/def ::carrier-result
  (s/and (s/keys :req-un [::status])
         (s/multi-spec carrier-result-status :status)))
