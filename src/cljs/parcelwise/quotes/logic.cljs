(ns parcelwise.quotes.logic
  "[Calculation] -- request construction, response decoding, and
  state-transition derivation for the Reagent quote form (spec section 10;
  spec 13.5; AC8, AC9).

  Every function here is a pure transform: plain Clojure maps in, plain
  Clojure maps out. No reagent atom, ajax.core, or DOM is touched -- those
  concerns live in `parcelwise.quotes.api` and `parcelwise.quotes.views`,
  which call into this namespace and apply the results as either an HTTP
  request body or a `state` update."
  (:require
    [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Field-error lookup (implementer-owned addition, spec 10.5)
;; ---------------------------------------------------------------------------

(defn- kebab->camel
  [kebab-name]
  (let [[first-part & rest-parts] (str/split kebab-name #"-")]
    (apply str first-part (map str/capitalize rest-parts))))

(defn field-error-key
  "The dotted-path camelCase keyword `decode-error` stores a given form
  field's server-reported errors under (spec 9.2, e.g.
  `:parcel.weightKg`). `section` is one of `:origin`/`:destination`/
  `:parcel`; `field` is the local kebab-case field keyword (e.g.
  `:weight-kg`). Mirrors the server's own
  `parcelwise.http.error-mapping/field-errors` camelCase transform exactly,
  so the view layer can look up per-field messages by re-deriving the same
  key from the field it's rendering, rather than hand-maintaining a second
  parallel lookup table."
  [section field]
  (keyword (str (name section) "." (kebab->camel (name field)))))

;; ---------------------------------------------------------------------------
;; Request construction (spec 9.1)
;; ---------------------------------------------------------------------------

(defn- build-address
  "Reshapes one kebab-case, string-valued address sub-form into the exact
  nested camelCase wire shape spec 9.1's example shows. A blank street-2
  becomes JSON null (Clojure nil) -- the more faithful translation of 'no
  suite/unit number entered' than an empty string (spec 6.1's `street-2` is
  `(s/nilable string?)` on the server). Every other field is passed through
  unchanged, blank or not: build-request performs no substantive
  validation of its own (spec 10.1 -- 'server validation remains
  authoritative')."
  [{:keys [name street-1 street-2 city region postal-code country-code]}]
  {:name        name
   :street1     street-1
   :street2     (when (seq street-2) street-2)
   :city        city
   :region      region
   :postalCode  postal-code
   :countryCode country-code})

(defn- build-parcel
  "Reshapes the kebab-case parcel sub-form into the camelCase wire shape.
  Values remain strings on the wire (spec 9.1) -- never coerced to
  numbers, and never rejected here even when blank/invalid (the server
  remains the sole validation authority, spec 10.1)."
  [{:keys [weight-kg length-cm width-cm height-cm]}]
  {:weightKg weight-kg
   :lengthCm length-cm
   :widthCm  width-cm
   :heightCm height-cm})

(defn build-request
  "Builds the exact nested, camelCase, JSON-ready request body Slice 2's
  server expects (spec 9.1) from `form` -- the `:form` sub-map of
  `parcelwise.quotes.state/initial-state`'s shape."
  [{:keys [origin destination parcel]}]
  {:origin      (build-address origin)
   :destination (build-address destination)
   :parcel      (build-parcel parcel)})

;; ---------------------------------------------------------------------------
;; State transition: idle -> loading (AC8, spec 10.2)
;; ---------------------------------------------------------------------------

(defn start-loading
  "Moves `state` into the `:loading` phase while preserving entered form
  values (spec 10.2) and clearing any field errors, error message, or
  pending focus request left over from a previous failed attempt, so a
  fresh submission never shows stale error content underneath a loading
  state."
  [state]
  (assoc state
         :phase :loading
         :field-errors {}
         :error-message nil
         :focus-error-summary? false))

;; ---------------------------------------------------------------------------
;; Response decoding -- success (spec 9.1, 9.5, AC7, AC8)
;; ---------------------------------------------------------------------------

(defn decode-success
  "Applies a successful `/api/quotes` response body (already decoded into a
  Clojure map with keyword keys matching the wire JSON verbatim, e.g.
  `:totalAmount`) to `state`. A non-empty `:quotes` moves to the
  `:results` phase with the quotes preserved in the exact order the
  (already server-sorted, spec 7.5) response gave them -- never re-sorted
  client-side. An empty `:quotes` moves to the `:empty` phase instead
  (spec 9.5 / AC7: 'The UI must present this as a valid empty result
  rather than an application error')."
  [state response-body]
  (let [quotes (:quotes response-body)]
    (assoc state
           :phase (if (seq quotes) :results :empty)
           :quotes (vec quotes)
           :field-errors {}
           :error-message nil
           :focus-error-summary? false)))

;; ---------------------------------------------------------------------------
;; Response decoding -- error (spec 9.2, 9.3, 9.4, 10.5, AC9)
;; ---------------------------------------------------------------------------

(defn- usable-field-errors
  "The decoded `:error :fields` map when it is present and non-empty,
  otherwise nil. A 400 response without usable per-field errors is treated
  as an unexpected/malformed shape, not a normal field-validation failure
  (see `decode-error`)."
  [response-body]
  (let [fields (get-in response-body [:error :fields])]
    (when (and (map? fields) (seq fields))
      fields)))

(defn- error-message
  "The human-readable message to show for a non-field-validation failure.
  Prefers the server's own `:error :message` when present (spec 9.3/9.4's
  literal carrier-unavailable/carrier-timeout text) and otherwise falls
  back to a fixed, generic string -- never echoing unrecognized body
  content such as raw exception/class names (spec 10.5: 'Do not display
  raw exceptions or provider payloads')."
  [status response-body]
  (let [message (get-in response-body [:error :message])]
    (cond
      (string? message) message
      (= status 400)    "The quote request is invalid."
      :else              "An unexpected error occurred. Please try again.")))

(defn decode-error
  "Applies an `/api/quotes` error response (`status` plus the decoded body)
  to `state`, covering all four of spec 10.5's error categories:

  - Invalid form data (400 with usable `:error :fields`): returns to the
    `:form` phase with per-field messages the form can show near each
    input (spec 10.5), the entered form values preserved, and
    `:focus-error-summary?` set (AC9: 'focus moves to an error summary').
  - Carrier unavailable (502), carrier timeout (504), or anything else --
    including a 400 whose body is missing or empties out `:fields`, a
    shape a real Slice 2 server should never send but a defensive boundary
    nonetheless -- moves to the `:error` phase with a human-readable
    message and the same focus-summary signal."
  [state status response-body]
  (if-let [fields (and (= status 400) (usable-field-errors response-body))]
    (assoc state
           :phase :form
           :field-errors fields
           :error-message nil
           :focus-error-summary? true)
    (assoc state
           :phase :error
           :field-errors {}
           :error-message (error-message status response-body)
           :focus-error-summary? true)))
