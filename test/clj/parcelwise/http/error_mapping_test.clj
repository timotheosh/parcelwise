(ns parcelwise.http.error-mapping-test
  "Behavioral tests for parcelwise.http.error-mapping -- turning
  clojure.spec explain-data into the required
  {\"parcel.weightKg\" [\"must be greater than zero\"]} field-path/message
  shape (spec 9.2), with camelCase field paths.

  Deliberately generates explain-data via the REAL, already-implemented
  parcelwise.specs/quote-request spec (Slice 1) rather than hand-constructed
  fake explain-data maps, so these tests exercise the actual shape
  clojure.spec produces for this project's specs (confirmed by direct
  REPL probe: :in path segments for a nested s/keys violation are the
  un-namespaced keyword path, e.g. [:parcel :weight-kg]; a *missing*
  required key instead reports :in as the path to the *containing* map,
  with the missing key name only recoverable from :pred's
  `(fn [%] (contains? % :the-missing-key))` shape -- error-mapping is
  expected to special-case this so a missing 'name' field still produces
  the useful, UI-actionable path \"origin.name\" rather than the less
  useful, ambiguous \"origin\").

  [Calculation tests] -- pure function: explain-data in, field-path map
  out. No ring/HTTP machinery in this file."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [parcelwise.http.error-mapping :as error-mapping]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

(defn- explain [quote-request]
  (s/explain-data ::specs/quote-request quote-request))

;; ---------------------------------------------------------------------------
;; The literal example from spec 9.2
;; ---------------------------------------------------------------------------

(deftest zero-weight-matches-spec-example-exactly-test
  (testing "spec 9.2's own literal example: zero weight -> exactly this field path and message"
    (is (= {:parcel.weightKg ["must be greater than zero"]}
           (error-mapping/field-errors
             (explain (ts/quote-request {:parcel (ts/parcel {:weight-kg 0})})))))))

(deftest negative-weight-same-rule-same-message-test
  (testing "negative weight fails the identical positive-number rule as zero, so it gets the identical message"
    (is (= {:parcel.weightKg ["must be greater than zero"]}
           (error-mapping/field-errors
             (explain (ts/quote-request {:parcel (ts/parcel {:weight-kg -3.2M})})))))))

;; ---------------------------------------------------------------------------
;; Field-path derivation and camelCase conversion
;; ---------------------------------------------------------------------------

(deftest invalid-dimension-field-paths-test
  (testing "each invalid parcel dimension is reported under its own camelCase dotted path"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:parcel (ts/parcel {:length-cm -1})})))]
      (is (contains? fields :parcel.lengthCm))
      (is (seq (:parcel.lengthCm fields)))
      (is (every? string? (:parcel.lengthCm fields))))))

(deftest missing-required-address-field-test
  (testing "a missing required address field is reported at a field-specific path, not just the containing object"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:origin (dissoc (ts/address) :name)})))]
      (is (contains? fields :origin.name))
      (is (seq (:origin.name fields))))))

(deftest blank-required-address-field-test
  (testing "a blank (empty-string) required address field is reported at its field path"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:destination (ts/destination-address {:city ""})})))]
      (is (contains? fields :destination.city))
      (is (seq (:destination.city fields))))))

(deftest malformed-country-code-field-path-test
  (testing "a malformed country code is reported at its own field path"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:origin (ts/address {:country-code "usa"})})))]
      (is (contains? fields :origin.countryCode))
      (is (seq (:origin.countryCode fields))))))

(deftest multi-segment-kebab-field-name-camel-cases-correctly-test
  (testing "billable-weight-kg-shaped multi-word kebab names camelCase correctly (postal-code -> postalCode)"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:origin (ts/address {:postal-code ""})})))]
      (is (contains? fields :origin.postalCode)))))

;; ---------------------------------------------------------------------------
;; AC2 / spec 9.2: all invalid fields reported in one pass
;; ---------------------------------------------------------------------------

(deftest multiple-simultaneous-invalid-fields-all-reported-test
  (testing "every invalid field is reported from a single explain-data pass, not just the first one found (spec 9.2, AC2)"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request
                              {:parcel (ts/parcel {:weight-kg 0 :length-cm -1})
                               :origin (ts/address {:country-code "usa"})})))]
      (is (contains? fields :parcel.weightKg))
      (is (contains? fields :parcel.lengthCm))
      (is (contains? fields :origin.countryCode))
      (is (= 3 (count fields))
          "exactly the three deliberately-invalid fields should be reported, nothing else"))))

;; ---------------------------------------------------------------------------
;; A fully valid request yields no field errors at all
;; ---------------------------------------------------------------------------

(deftest valid-request-yields-no-field-errors-test
  (testing "a spec-valid quote request produces nil explain-data, which field-errors maps to an empty fields map"
    (is (nil? (explain (ts/quote-request))))
    (is (= {} (error-mapping/field-errors (explain (ts/quote-request)))))))
