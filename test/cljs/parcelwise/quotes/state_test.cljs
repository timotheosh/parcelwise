(ns parcelwise.quotes.state-test
  "Behavioral tests for parcelwise.quotes.state -- [Data]: the reagent atom
  and its initial-state shape (spec section 10, plan's frontend namespace
  layout). `initial-state` is the fixture the logic-test namespace reuses
  as the starting point for every state-transition test, so its shape is
  pinned down precisely here first."
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [pjstadig.humane-test-output]
            [parcelwise.quotes.state :as state]))

(deftest initial-phase-is-form-test
  (testing "the form is the default/idle phase (spec 10.1)"
    (is (= :form (:phase state/initial-state)))))

(deftest initial-form-values-are-blank-with-us-default-country-test
  (testing "origin/destination address fields start blank, country-code defaults to US (spec 6.1: 'The initial UI may default country-code to \"US\"')"
    (let [blank-address {:name "" :street-1 "" :street-2 ""
                          :city "" :region "" :postal-code ""
                          :country-code "US"}]
      (is (= blank-address (get-in state/initial-state [:form :origin])))
      (is (= blank-address (get-in state/initial-state [:form :destination])))))
  (testing "parcel measurement fields start blank"
    (is (= {:weight-kg "" :length-cm "" :width-cm "" :height-cm ""}
           (get-in state/initial-state [:form :parcel])))))

(deftest initial-state-has-no-quotes-or-errors-test
  (testing "no quotes, no field errors, no error message, no pending focus request before any submission"
    (is (= [] (:quotes state/initial-state)))
    (is (= {} (:field-errors state/initial-state)))
    (is (nil? (:error-message state/initial-state)))
    (is (false? (:focus-error-summary? state/initial-state)))))

(deftest state-atom-is-initialized-to-initial-state-test
  (testing "the mutable reagent atom other namespaces read/swap! starts out equal to initial-state"
    (is (= state/initial-state @state/state))))
