(ns parcelwise.http.json-boundary-test
  "Behavioral tests for parcelwise.http.json-boundary -- the explicit
  camelCase<->kebab-case key transform and BigDecimal<->decimal-string
  conversion described in the approved plan's `http/json_boundary.clj`
  entry, covering spec sections 6.2 ('Reject malformed numeric strings at
  the HTTP boundary'), 6.4 ('Do not expose Java or Clojure
  implementation-specific number representations'), and 13.2 ('Monetary
  serialization').

  [Calculation tests] -- every assertion here is against a pure function:
  given input, does it return the documented explicit output? No I/O, no
  ring/HTTP machinery involved at all in this file."
  (:require
    [clojure.test :refer :all]
    [parcelwise.http.json-boundary :as json-boundary]
    [parcelwise.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Decimal <-> string, spec 13.2 / 6.4
;; ---------------------------------------------------------------------------

(deftest encode-decimal-test
  (testing "formats a BigDecimal money value exactly as spec 9.1's own example"
    (is (= "12.50" (json-boundary/encode-decimal 12.50M)))
    (is (= "1.25" (json-boundary/encode-decimal 1.25M)))
    (is (= "13.75" (json-boundary/encode-decimal 13.75M))))

  (testing "formats a BigDecimal weight value without padding, as spec 9.1's own example"
    (is (= "3.2" (json-boundary/encode-decimal 3.2M))))

  (testing "never emits scientific/exponential notation, even for very small-scale BigDecimals"
    (let [encoded (json-boundary/encode-decimal (bigdec "0.0000000123"))]
      (is (= "0.0000000123" encoded))
      (is (not (re-find #"[eE]" encoded)))))

  (testing "never leaks a Java/Clojure BigDecimal literal suffix or type tag"
    (let [encoded (json-boundary/encode-decimal 1M)]
      (is (string? encoded))
      (is (not (re-find #"[Mm]$" encoded)))
      (is (not (re-find #"BigDecimal" encoded)))))

  (testing "returns a plain string, not a number, so downstream JSON encoding always produces a JSON string"
    (is (string? (json-boundary/encode-decimal 42M)))))

(deftest decode-decimal-string-test
  (testing "parses a valid decimal string into a BigDecimal"
    (is (= 3.2M (json-boundary/decode-decimal-string "3.2")))
    (is (= 30M (json-boundary/decode-decimal-string "30")))
    (is (decimal? (json-boundary/decode-decimal-string "3.2"))))

  (testing "rejects malformed numeric strings (spec 6.2) by returning nil rather than throwing or silently coercing"
    (is (nil? (json-boundary/decode-decimal-string "not-a-number")))
    (is (nil? (json-boundary/decode-decimal-string "")))
    (is (nil? (json-boundary/decode-decimal-string "3.2abc")))
    (is (nil? (json-boundary/decode-decimal-string "NaN")))
    (is (nil? (json-boundary/decode-decimal-string "Infinity"))))

  (testing "never throws on malformed input"
    (is (nil? (json-boundary/decode-decimal-string nil)))))

;; ---------------------------------------------------------------------------
;; Full request/response boundary transforms
;; ---------------------------------------------------------------------------

(deftest decode-quote-request-test
  (testing "decodes spec 9.1's exact example request into the domain quote-request shape"
    (is (= (ts/quote-request)
           (json-boundary/decode-quote-request (ts/json-quote-request)))))

  (testing "decodes dimensional-weight-driving parcel measurements correctly (round-trip against a distinct fixture)"
    (is (= {:origin      (ts/address)
            :destination (ts/destination-address)
            :parcel      {:weight-kg 5.0M :length-cm 60.0M :width-cm 50.0M :height-cm 40.0M}}
           (json-boundary/decode-quote-request
             (ts/json-quote-request
               {:parcel (ts/json-parcel {:weightKg "5.0" :lengthCm "60" :widthCm "50" :heightCm "40"})})))))

  (testing "preserves an explicit null street2 as nil"
    (is (nil? (:street-2 (:origin (json-boundary/decode-quote-request (ts/json-quote-request)))))))

  (testing "a malformed numeric parcel field does not throw -- it decodes to a non-numeric value so the domain spec can reject it with a field error downstream"
    (let [decoded (json-boundary/decode-quote-request
                    (ts/json-quote-request
                      {:parcel (ts/json-parcel {:weightKg "not-a-number"})}))]
      (is (not (number? (get-in decoded [:parcel :weight-kg])))))))

(deftest encode-quote-response-test
  (testing "encodes a normalized quote into spec 9.1's exact example response shape"
    (is (= {:quotes [(ts/json-normalized-quote)]}
           (json-boundary/encode-quote-response [(ts/normalized-quote)]))))

  (testing "encodes multiple quotes preserving order"
    (let [quotes [(ts/normalized-quote {:id "fake-ground" :total-amount 13.75M})
                  (ts/normalized-quote {:id "fake-express" :total-amount 30.50M})]
          encoded (json-boundary/encode-quote-response quotes)]
      (is (= ["fake-ground" "fake-express"] (map :id (:quotes encoded))))))

  (testing "encodes an empty quote list as an empty array, not nil or a missing key (AC7)"
    (is (= {:quotes []} (json-boundary/encode-quote-response []))))

  (testing "omits estimatedDays when the source quote has no estimated-days (spec 6.4: may be absent)"
    (let [encoded (json-boundary/encode-quote-response
                    [(dissoc (ts/normalized-quote) :estimated-days)])]
      (is (not (contains? (first (:quotes encoded)) :estimatedDays)))))

  (testing "every money/weight field in the encoded output is a string, never a bare number, never a double"
    (let [quote (-> (json-boundary/encode-quote-response [(ts/normalized-quote)])
                     :quotes
                     first)]
      (doseq [k [:baseAmount :surchargeAmount :totalAmount :billableWeightKg]]
        (is (string? (get quote k)) (str k " must encode as a string"))))))
