(ns parcelwise.quotes.logic-impl-test
  "Implementer-owned coverage for `parcelwise.quotes.logic` additions not
  fixed by the independent test-designer contract (`logic_test.cljs`) --
  specifically `field-error-key`, added so `parcelwise.quotes.views` can
  look up a given form field's server-reported error by re-deriving the
  same camelCase dotted-path key `parcelwise.http.error-mapping/field-errors`
  produces server-side, rather than hand-maintaining a second lookup table.

  Does not modify, weaken, or duplicate any independent test-designer
  file."
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [pjstadig.humane-test-output]
            [parcelwise.quotes.logic :as logic]))

(deftest field-error-key-single-word-field-test
  (testing "a single-word field name needs no camelCase transform"
    (is (= :origin.name (logic/field-error-key :origin :name)))
    (is (= :destination.city (logic/field-error-key :destination :city)))))

(deftest field-error-key-hyphenated-field-test
  (testing "a hyphenated kebab-case field name becomes camelCase, matching the server's own error-mapping transform exactly"
    (is (= :parcel.weightKg (logic/field-error-key :parcel :weight-kg)))
    (is (= :origin.street1 (logic/field-error-key :origin :street-1)))
    (is (= :origin.postalCode (logic/field-error-key :origin :postal-code)))
    (is (= :destination.countryCode (logic/field-error-key :destination :country-code)))))
