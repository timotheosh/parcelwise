(ns parcelwise.quotes.state
  "[Data] -- the quote form's mutable source of truth and its initial shape.

  `initial-state` is a plain Clojure map (spec section 10; plan's frontend
  namespace layout) so `parcelwise.quotes.logic`'s pure calculation
  functions can be tested and reasoned about with plain map fixtures,
  without touching the reagent atom at all. `state` is the single
  `reagent.core/atom` that `parcelwise.quotes.views` reads from and
  `parcelwise.quotes.api` `reset!`s/`swap!`s into via `logic`'s pure
  transition functions -- the only place in this feature where mutable
  state actually lives."
  (:require
    [reagent.core :as r]))

(def ^:private blank-address
  "Address sub-form fields blank on load; `country-code` defaults to
  \"US\" per spec 6.1 ('The initial UI may default country-code to
  \"US\"')."
  {:name "" :street-1 "" :street-2 ""
   :city "" :region "" :postal-code "" :country-code "US"})

(def ^:private blank-parcel
  {:weight-kg "" :length-cm "" :width-cm "" :height-cm ""})

(def initial-state
  "The quote form's starting shape (spec 10.1, 6.1).

  :phase                 -- :form | :loading | :results | :empty | :error
  :form                  -- {:origin <address> :destination <address> :parcel <parcel>},
                             kebab-case keyword keys with string values,
                             matching what raw HTML form inputs naturally produce
  :quotes                -- vector of decoded quote maps, server order preserved
  :field-errors          -- map of dotted-path keyword -> [message ...] (spec 9.2)
  :error-message         -- string | nil, human-readable, never raw exception/provider text (spec 10.5)
  :focus-error-summary?  -- boolean; the view watches this to move DOM focus
                             after a failed submission (AC9 / spec 10.6)"
  {:phase :form
   :form {:origin      blank-address
          :destination blank-address
          :parcel      blank-parcel}
   :quotes []
   :field-errors {}
   :error-message nil
   :focus-error-summary? false})

(defonce state
  (r/atom initial-state))
