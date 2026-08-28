(ns parcelwise.domain.quote-engine
  "Pure domain calculations for turning raw carrier rates into the stable,
  application-owned normalized quote model (spec sections 7.3, 7.4, 7.5).

  [Calculation] -- every function here depends only on its explicit
  arguments, returns an explicit value, and performs no I/O, mutation,
  clock access, or logging."
  (:require
    [clojure.string :as str]))

(defn- blank-string?
  [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))))

(defn- finite-number?
  "A number is finite when it is not NaN and not +/- Infinity. Mirrors
  the identical predicate in parcelwise.specs (spec 6.2's 'finite'
  requirement) -- non-finite doubles satisfy `number?` but are never
  valid business quantities."
  [v]
  (and (number? v)
       (not (and (double? v) (or (Double/isNaN v) (Double/isInfinite v))))))

(defn- valid-total-amount?
  [total-amount]
  (and (finite-number? total-amount)
       (not (neg? total-amount))))

(defn- valid-rate?
  "A single raw carrier rate is valid when it satisfies every exclusion
  rule in spec 7.4: it has a stable id, carrier and service information,
  a currency, a valid non-negative total price, and is not explicitly
  reported unavailable by the provider."
  [{:keys [id carrier service-code service-name currency total-amount available?]}]
  (and (not (blank-string? id))
       (not (blank-string? carrier))
       (not (blank-string? service-code))
       (not (blank-string? service-name))
       (not (blank-string? currency))
       (valid-total-amount? total-amount)
       (not (false? available?))))

(defn filter-valid-rates
  "Drops raw carrier rates that fail any of spec 7.4's exclusion rules.
  One malformed rate never invalidates the other valid rates in the same
  provider response (AC5)."
  [raw-rates]
  (filterv valid-rate? raw-rates))

(defn normalize-rate
  "Converts one valid raw carrier rate into the full normalized quote
  shape (spec 6.4), merging in the request-derived :billable-weight-kg
  and :provider from `context` (a map of
  {:billable-weight-kg <BigDecimal> :provider <keyword>}). Drops the
  raw-only :available? filtering flag."
  [raw-rate context]
  (-> raw-rate
      (dissoc :available?)
      (merge (select-keys context [:billable-weight-kg :provider]))))

(defn- estimated-days-sort-key
  "Missing estimated-days must sort after known estimates at the same
  price (spec 7.5.2). Represent 'missing' as a value guaranteed to sort
  after every real (non-negative) estimate."
  [estimated-days]
  (if (some? estimated-days)
    [0 estimated-days]
    [1 0]))

(defn- ordering-key
  [{:keys [total-amount estimated-days carrier service-name id]}]
  [total-amount (estimated-days-sort-key estimated-days) carrier service-name id])

(defn order-quotes
  "Sorts normalized quotes per spec 7.5's deterministic 5-key ordering:
  total-amount ascending, then estimated-days ascending with missing
  estimates sorted last, then carrier alphabetically, then service-name
  alphabetically, then id as a final tie-breaker. The same input
  collection always produces the same ordering."
  [quotes]
  (vec (sort-by ordering-key quotes)))
