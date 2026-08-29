(ns parcelwise.quotes.logic-test
  "Behavioral tests for parcelwise.quotes.logic -- [Calculation]: request
  construction, response decoding, and state-transition derivation for the
  Reagent quote form (spec section 10; spec 13.5 frontend testing
  responsibilities; AC8, AC9).

  All functions under test are pure: given plain Clojure maps in, they
  return plain Clojure maps out. No reagent atom, ajax.core, or DOM is
  touched anywhere in this file -- that is deliberate, see
  `.ai/test-plan.md`'s Slice 3 section for the reasoning on what is and is
  not in scope for automated `cljs.test` coverage at this layer.

  `parcelwise.quotes.state/initial-state` is reused as the fixture starting
  point for every state-transition test below, instead of re-declaring an
  equivalent literal here, so this file stays coupled to the single
  `state.cljs` shape the state-test namespace already pins down."
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [pjstadig.humane-test-output]
            [parcelwise.quotes.state :as state]
            [parcelwise.quotes.logic :as logic]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def valid-form
  "Mirrors spec 9.1's own example request, but in the kebab-case,
  string-valued shape the UI's local :form state holds (matching what raw
  HTML form inputs naturally produce)."
  {:origin      {:name "Jane Example" :street-1 "123 Market Street" :street-2 ""
                 :city "Philadelphia" :region "PA" :postal-code "19103" :country-code "US"}
   :destination {:name "John Example" :street-1 "500 Howard Street" :street-2 ""
                 :city "San Francisco" :region "CA" :postal-code "94105" :country-code "US"}
   :parcel      {:weight-kg "3.2" :length-cm "30" :width-cm "20" :height-cm "15"}})

(def expected-wire-request
  "The exact nested camelCase JSON-ready shape spec 9.1's example shows,
  including blank street-2 encoded as JSON null rather than an empty
  string (spec 6.1: street-2 is optional/nilable, not merely an
  empty-allowed required string)."
  {:origin      {:name "Jane Example" :street1 "123 Market Street" :street2 nil
                 :city "Philadelphia" :region "PA" :postalCode "19103" :countryCode "US"}
   :destination {:name "John Example" :street1 "500 Howard Street" :street2 nil
                 :city "San Francisco" :region "CA" :postalCode "94105" :countryCode "US"}
   :parcel      {:weightKg "3.2" :lengthCm "30" :widthCm "20" :heightCm "15"}})

(def sample-quote-ground
  "Matches spec 9.1's own example response quote verbatim (already-decoded
  camelCase keys, decimal fields as strings -- this is what the response
  arrives as once the (implementer-owned) api.cljs layer has run the HTTP
  response through :response-format :json)."
  {:id "fake-ground" :carrier "Example Carrier" :serviceCode "ground"
   :serviceName "Ground" :currency "USD" :baseAmount "12.50"
   :surchargeAmount "1.25" :totalAmount "13.75" :estimatedDays 4
   :billableWeightKg "3.2" :provider "fake"})

(def sample-quote-express
  {:id "fake-express" :carrier "Example Carrier" :serviceCode "express"
   :serviceName "Express" :currency "USD" :baseAmount "20.00"
   :surchargeAmount "2.00" :totalAmount "22.00" :estimatedDays 1
   :billableWeightKg "3.2" :provider "fake"})

(def field-error-body
  "Matches spec 9.2's literal 400 example, decoded, plus a second field to
  exercise the 'return all useful errors discovered in one pass' shape."
  {:error {:code "invalid-request"
           :message "The quote request is invalid."
           :fields {:parcel.weightKg ["must be greater than zero"]
                    :origin.name ["is required"]}}})

(def carrier-unavailable-body
  {:error {:code "carrier-unavailable"
           :message "Shipping rates are temporarily unavailable."}})

(def carrier-timeout-body
  {:error {:code "carrier-timeout"
           :message "The shipping-rate request timed out."}})

;; ---------------------------------------------------------------------------
;; Request construction (spec 9.1, 13.5)
;; ---------------------------------------------------------------------------

(deftest build-request-produces-the-exact-wire-shape-test
  (testing "form values (kebab-case, string) build the exact nested camelCase JSON body Slice 2's server expects"
    (is (= expected-wire-request (logic/build-request valid-form)))))

(deftest build-request-passes-through-a-filled-in-street2-test
  (testing "a non-blank street-2 is sent as a string, not dropped or nulled"
    (let [form (assoc-in valid-form [:origin :street-2] "Suite 400")]
      (is (= "Suite 400" (get-in (logic/build-request form) [:origin :street2]))))))

(deftest build-request-keeps-parcel-fields-as-strings-test
  (testing "parcel measurement fields remain strings on the wire (spec 9.1 -- not coerced to JS/Clojure numbers)"
    (let [req (logic/build-request valid-form)]
      (doseq [k [:weightKg :lengthCm :widthCm :heightCm]]
        (is (string? (get-in req [:parcel k])))))))

(deftest build-request-does-not-perform-substantive-validation-test
  (testing "build-request is a pure data transform, not a validator -- blank/invalid values pass through unchanged so the server remains the sole authority (spec 10.1: 'server validation remains authoritative')"
    (let [blank-form (assoc-in valid-form [:parcel :weight-kg] "")]
      (is (= "" (get-in (logic/build-request blank-form) [:parcel :weightKg]))))))

;; ---------------------------------------------------------------------------
;; State transition: idle -> loading (AC8)
;; ---------------------------------------------------------------------------

(deftest start-loading-sets-loading-phase-and-preserves-form-test
  (testing "AC8 / spec 10.2: submitting moves to :loading, a single phase value the view can use to both disable the submit button and show a loading message"
    (let [before (assoc state/initial-state :form valid-form)
          after (logic/start-loading before)]
      (is (= :loading (:phase after)))
      (testing "entered form values are preserved, not cleared, while the request is active"
        (is (= valid-form (:form after))))))
  (testing "starting a new submission clears any errors left over from a previous failed attempt"
    (let [before (-> state/initial-state
                      (assoc :field-errors {:parcel.weightKg ["must be greater than zero"]})
                      (assoc :error-message "Shipping rates are temporarily unavailable."))
          after (logic/start-loading before)]
      (is (= {} (:field-errors after)))
      (is (nil? (:error-message after))))))

;; ---------------------------------------------------------------------------
;; Response decoding -- success (spec 9.1, 9.5, AC7, AC8)
;; ---------------------------------------------------------------------------

(deftest decode-success-with-quotes-yields-results-phase-in-server-order-test
  (testing "AC8: a non-empty quotes body transitions to :results, showing quotes in the order the (already server-sorted) response gave them"
    (let [loading (logic/start-loading (assoc state/initial-state :form valid-form))
          result (logic/decode-success loading {:quotes [sample-quote-ground sample-quote-express]})]
      (is (= :results (:phase result)))
      (is (= [sample-quote-ground sample-quote-express] (:quotes result)))
      (testing "form values remain preserved once results arrive"
        (is (= valid-form (:form result))))))
  (testing "server-given order is preserved exactly, never re-derived/re-sorted client-side -- a reversed input order stays reversed"
    (let [loading (logic/start-loading state/initial-state)
          result (logic/decode-success loading {:quotes [sample-quote-express sample-quote-ground]})]
      (is (= [sample-quote-express sample-quote-ground] (:quotes result))))))

(deftest decode-success-with-empty-quotes-yields-empty-phase-test
  (testing "spec 9.5 / AC7: an empty quotes array is a distinct valid empty result, not an error"
    (let [loading (logic/start-loading state/initial-state)
          result (logic/decode-success loading {:quotes []})]
      (is (= :empty (:phase result)))
      (is (= [] (:quotes result)))
      (is (nil? (:error-message result)))
      (is (= {} (:field-errors result))))))

;; ---------------------------------------------------------------------------
;; Response decoding -- error (spec 9.2, 9.3, 9.4, 10.5, AC9)
;; ---------------------------------------------------------------------------

(deftest decode-error-400-maps-field-errors-and-returns-to-form-phase-test
  (testing "spec 9.2/10.5: field-validation failures return to (or stay in) :form phase, not the generic :error phase, with per-field messages the form can show near each input"
    (let [loading (logic/start-loading (assoc state/initial-state :form valid-form))
          result (logic/decode-error loading 400 field-error-body)]
      (is (= :form (:phase result)))
      (is (= {:parcel.weightKg ["must be greater than zero"]
              :origin.name ["is required"]}
             (:field-errors result)))
      (testing "AC9: focus moves to an error summary"
        (is (true? (:focus-error-summary? result))))
      (testing "no generic error banner accompanies a pure field-validation failure"
        (is (nil? (:error-message result))))
      (testing "entered form values are preserved so the user can fix only the invalid fields"
        (is (= valid-form (:form result)))))))

(deftest decode-error-400-without-usable-fields-falls-back-to-generic-error-test
  (testing "a 400 body missing/emptying the expected :fields object must not silently return to :form with zero visible errors -- that would strand the user with a disabled-looking form and no feedback"
    (let [loading (logic/start-loading state/initial-state)]
      (doseq [malformed-body [{:error {:code "invalid-request" :message "The quote request is invalid."}}
                               {:error {:code "invalid-request" :message "The quote request is invalid." :fields {}}}]]
        (let [result (logic/decode-error loading 400 malformed-body)]
          (is (= :error (:phase result)))
          (is (= "The quote request is invalid." (:error-message result))))))))

(deftest decode-error-502-yields-error-phase-with-human-message-test
  (testing "spec 9.3/10.5: carrier-unavailable is a distinct, human-readable error state"
    (let [loading (logic/start-loading state/initial-state)
          result (logic/decode-error loading 502 carrier-unavailable-body)]
      (is (= :error (:phase result)))
      (is (= "Shipping rates are temporarily unavailable." (:error-message result)))
      (is (= {} (:field-errors result)))
      (testing "AC9-style focus-to-summary applies to carrier failures too, not just field errors"
        (is (true? (:focus-error-summary? result)))))))

(deftest decode-error-504-yields-error-phase-with-human-message-test
  (testing "spec 9.4/10.5: carrier-timeout is distinguishable from carrier-unavailable by message, both land in :error phase"
    (let [loading (logic/start-loading state/initial-state)
          result (logic/decode-error loading 504 carrier-timeout-body)]
      (is (= :error (:phase result)))
      (is (= "The shipping-rate request timed out." (:error-message result))))))

(deftest decode-error-never-leaks-raw-payload-for-an-unrecognized-failure-test
  (testing "spec 10.5: 'Do not display raw exceptions or provider payloads' -- an unexpected/malformed error body (e.g. a 500 with no recognizable error envelope) still yields a safe, human-readable message, never the raw body/exception text"
    (let [loading (logic/start-loading state/initial-state)
          raw-garbage {:stackTrace "java.lang.NullPointerException at parcelwise.internal.Foo"
                       :class "SomeInternalProviderException"}
          result (logic/decode-error loading 500 raw-garbage)]
      (is (= :error (:phase result)))
      (is (string? (:error-message result)))
      (is (not (re-find #"(?i)exception|stacktrace|nullpointer" (:error-message result)))
          "error message must not echo raw exception/class names from an unrecognized body"))))

;; ---------------------------------------------------------------------------
;; AC8 / AC9 -- full submission lifecycle coverage
;; ---------------------------------------------------------------------------

(deftest submission-lifecycle-idle-to-loading-to-success-test
  (testing "AC8: idle -> loading (disabled/loading, form preserved) -> success (results in server order, form still preserved)"
    (let [idle (assoc state/initial-state :form valid-form)
          loading (logic/start-loading idle)
          done (logic/decode-success loading {:quotes [sample-quote-ground sample-quote-express]})]
      (is (= :form (:phase idle)))
      (is (= :loading (:phase loading)))
      (is (= valid-form (:form loading)) "loading must preserve entered values, not clear them")
      (is (= :results (:phase done)))
      (is (= [sample-quote-ground sample-quote-express] (:quotes done)))
      (is (= valid-form (:form done))))))

(deftest submission-lifecycle-loading-to-field-error-test
  (testing "AC9: loading -> field-validation failure keeps entered values, surfaces per-field errors, and requests focus on an error summary"
    (let [loading (logic/start-loading (assoc state/initial-state :form valid-form))
          failed (logic/decode-error loading 400 field-error-body)]
      (is (= :form (:phase failed)))
      (is (= valid-form (:form failed)))
      (is (seq (:field-errors failed)))
      (is (true? (:focus-error-summary? failed))))))

(deftest submission-lifecycle-loading-to-carrier-error-test
  (testing "AC9-adjacent: loading -> carrier failure (not a field-validation failure) keeps entered values and requests focus on an error summary too"
    (let [loading (logic/start-loading (assoc state/initial-state :form valid-form))
          failed (logic/decode-error loading 502 carrier-unavailable-body)]
      (is (= :error (:phase failed)))
      (is (= valid-form (:form failed)))
      (is (true? (:focus-error-summary? failed))))))
