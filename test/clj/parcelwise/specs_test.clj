(ns parcelwise.specs-test
  "Slice 1 behavioral tests for spec section 6 (Domain Model) as
  clojure.spec definitions, plus the carrier-adapter result shape from
  section 8. Operates on already-typed Clojure data (numbers/BigDecimals),
  not raw HTTP/JSON strings -- HTTP-boundary malformed-string rejection is
  Slice 2's concern."
  (:require
    [clojure.test :refer :all]
    [clojure.spec.alpha :as s]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; 6.1 Address
;; ---------------------------------------------------------------------------

(deftest address-spec-test
  (testing "a fully valid address is valid"
    (is (s/valid? ::specs/address (ts/address))))

  (testing "street-2 is optional and may be nil or a value"
    (is (s/valid? ::specs/address (ts/address {:street-2 nil})))
    (is (s/valid? ::specs/address (ts/address {:street-2 "Suite 400"}))))

  (testing "required string fields must be present"
    (doseq [field [:name :street-1 :city :region :postal-code :country-code]]
      (is (not (s/valid? ::specs/address (dissoc (ts/address) field)))
          (str field " missing should be invalid"))))

  (testing "required string fields must not be blank"
    (doseq [field [:name :street-1 :city :region :postal-code]]
      (is (not (s/valid? ::specs/address (ts/address {field ""})))
          (str field " blank should be invalid"))))

  (testing "country-code must be a two-character uppercase code"
    (is (s/valid? ::specs/address (ts/address {:country-code "US"})))
    (is (not (s/valid? ::specs/address (ts/address {:country-code "us"}))))
    (is (not (s/valid? ::specs/address (ts/address {:country-code "USA"}))))
    (is (not (s/valid? ::specs/address (ts/address {:country-code "U1"}))))
    (is (not (s/valid? ::specs/address (ts/address {:country-code ""})))))

  (testing "explain-data reports a problem for invalid data"
    (is (some? (s/explain-data ::specs/address (ts/address {:country-code "us"}))))))

;; ---------------------------------------------------------------------------
;; 6.2 Parcel
;; ---------------------------------------------------------------------------

(deftest parcel-spec-test
  (testing "a fully valid parcel is valid"
    (is (s/valid? ::specs/parcel (ts/parcel))))

  (testing "every field is required"
    (doseq [field [:weight-kg :length-cm :width-cm :height-cm]]
      (is (not (s/valid? ::specs/parcel (dissoc (ts/parcel) field)))
          (str field " missing should be invalid"))))

  (testing "every field must be greater than zero: zero is rejected"
    (doseq [field [:weight-kg :length-cm :width-cm :height-cm]]
      (is (not (s/valid? ::specs/parcel (ts/parcel {field 0M})))
          (str field " zero should be invalid"))))

  (testing "every field must be greater than zero: negative is rejected"
    (doseq [field [:weight-kg :length-cm :width-cm :height-cm]]
      (is (not (s/valid? ::specs/parcel (ts/parcel {field -1.0M})))
          (str field " negative should be invalid")))))

;; ---------------------------------------------------------------------------
;; 6.3 Quote request
;; ---------------------------------------------------------------------------

(deftest quote-request-spec-test
  (testing "a fully valid quote request is valid"
    (is (s/valid? ::specs/quote-request (ts/quote-request))))

  (testing "missing origin, destination, or parcel is invalid"
    (is (not (s/valid? ::specs/quote-request (dissoc (ts/quote-request) :origin))))
    (is (not (s/valid? ::specs/quote-request (dissoc (ts/quote-request) :destination))))
    (is (not (s/valid? ::specs/quote-request (dissoc (ts/quote-request) :parcel)))))

  (testing "an invalid nested address invalidates the whole request"
    (is (not (s/valid? ::specs/quote-request
                        (ts/quote-request {:origin (ts/address {:country-code "usa"})})))))

  (testing "an invalid nested parcel invalidates the whole request"
    (is (not (s/valid? ::specs/quote-request
                        (ts/quote-request {:parcel (ts/parcel {:weight-kg 0M})}))))))

;; ---------------------------------------------------------------------------
;; 6.4 Normalized quote
;; ---------------------------------------------------------------------------

(deftest normalized-quote-spec-test
  (testing "a fully populated normalized quote is valid"
    (is (s/valid? ::specs/normalized-quote (ts/normalized-quote))))

  (testing "estimated-days may be absent"
    (is (s/valid? ::specs/normalized-quote (dissoc (ts/normalized-quote) :estimated-days))))

  (testing "every other field is required"
    (doseq [field [:id :carrier :service-code :service-name :currency
                   :base-amount :surcharge-amount :total-amount
                   :billable-weight-kg :provider]]
      (is (not (s/valid? ::specs/normalized-quote (dissoc (ts/normalized-quote) field)))
          (str field " missing should be invalid")))))

;; ---------------------------------------------------------------------------
;; 8. Carrier-adapter result shape
;; ---------------------------------------------------------------------------

(deftest carrier-result-spec-test
  (testing "a successful result with rates is valid"
    (is (s/valid? ::specs/carrier-result {:status :ok :rates [(ts/raw-rate)]})))

  (testing "a successful result may have an empty rates list (no usable rates, spec 9.5/AC7)"
    (is (s/valid? ::specs/carrier-result {:status :ok :rates []})))

  (testing "an error result requires a recognized, granular error-type (spec 8.2)"
    (doseq [error-type [:timeout :auth-failure :rate-limited :validation-error :upstream-error]]
      (is (s/valid? ::specs/carrier-result {:status :error :error-type error-type})
          (str error-type " should be a recognized error-type"))))

  (testing "an error result with an unrecognized error-type is invalid"
    (is (not (s/valid? ::specs/carrier-result {:status :error :error-type :something-else}))))

  (testing "an unknown status is invalid"
    (is (not (s/valid? ::specs/carrier-result {:status :maybe :rates []})))))
