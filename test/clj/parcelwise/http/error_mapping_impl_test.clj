(ns parcelwise.http.error-mapping-impl-test
  "Implementer-owned unit tests covering implementation-level branches of
  parcelwise.http.error-mapping/field-errors that the independent
  behavioral test suite (error_mapping_test.clj) does not directly lock
  down: the fallback ('is invalid') message for a predicate that is
  neither the missing-required-key form nor a named domain predicate, and
  the missing-required-key special-casing generalized beyond address
  fields (parcel, and the top-level quote-request itself). Does not touch
  or weaken the independent suite."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [parcelwise.http.error-mapping :as error-mapping]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

(defn- explain [quote-request]
  (s/explain-data ::specs/quote-request quote-request))

(deftest fallback-message-for-unnamed-predicate-test
  (testing "a malformed country code fails an anonymous regex predicate (neither missing-key nor a named domain predicate) -- locks in the fallback message"
    (is (= {:origin.countryCode ["is invalid"]}
           (error-mapping/field-errors
             (explain (ts/quote-request {:origin (ts/address {:country-code "usa"})})))))))

(deftest missing-key-detection-generalizes-beyond-address-test
  (testing "a missing required parcel field is reported at its own field path, not just missing-name-in-address"
    (let [fields (error-mapping/field-errors
                   (explain (ts/quote-request {:parcel (dissoc (ts/parcel) :weight-kg)})))]
      (is (contains? fields :parcel.weightKg))
      (is (= ["is required"] (:parcel.weightKg fields))))))

(deftest missing-top-level-required-key-test
  (testing "a missing top-level quote-request key (e.g. :parcel entirely absent) is reported at its own field path"
    (let [fields (error-mapping/field-errors
                   (explain (dissoc (ts/quote-request) :parcel)))]
      (is (contains? fields :parcel))
      (is (= ["is required"] (:parcel fields))))))
