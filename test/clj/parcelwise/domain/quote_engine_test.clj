(ns parcelwise.domain.quote-engine-test
  "Slice 1 behavioral tests for spec sections 7.3 (normalization), 7.4
  (filtering), and 7.5 (ordering); AC5 and the ordering-related AC13.1
  requirements (deterministic ordering, missing estimate sorts last)."
  (:require
    [clojure.test :refer :all]
    [parcelwise.domain.quote-engine :as qe]
    [parcelwise.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; 7.3 Quote normalization
;; ---------------------------------------------------------------------------

(deftest normalize-rate-test
  (testing "produces the full normalized quote shape (spec 6.4) from a raw carrier rate plus request-derived context"
    (let [raw (ts/raw-rate)
          normalized (qe/normalize-rate raw {:billable-weight-kg 3.2M :provider :fake})]
      (is (= (ts/normalized-quote) normalized))))

  (testing "does not leak the internal-only :available? filtering flag into the normalized shape"
    (let [normalized (qe/normalize-rate (ts/raw-rate) {:billable-weight-kg 3.2M :provider :fake})]
      (is (not (contains? normalized :available?)))))

  (testing "estimated-days is absent/nil in the normalized quote when the raw rate does not supply it"
    (let [raw (dissoc (ts/raw-rate) :estimated-days)
          normalized (qe/normalize-rate raw {:billable-weight-kg 3.2M :provider :fake})]
      (is (nil? (:estimated-days normalized)))))

  (testing "billable-weight-kg and provider come from the supplied context, not the raw rate"
    (let [normalized (qe/normalize-rate (ts/raw-rate) {:billable-weight-kg 24.0M :provider :shippo})]
      (is (= 24.0M (:billable-weight-kg normalized)))
      (is (= :shippo (:provider normalized))))))

;; ---------------------------------------------------------------------------
;; 7.4 Quote filtering (AC5)
;; ---------------------------------------------------------------------------

(deftest filter-valid-rates-test
  (testing "keeps a fully valid rate"
    (is (= [(ts/raw-rate)] (qe/filter-valid-rates [(ts/raw-rate)]))))

  (testing "drops a rate with no stable identifier"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:id nil})])))
    (is (empty? (qe/filter-valid-rates [(dissoc (ts/raw-rate) :id)]))))

  (testing "drops a rate missing carrier information"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:carrier nil})])))
    (is (empty? (qe/filter-valid-rates [(dissoc (ts/raw-rate) :carrier)]))))

  (testing "drops a rate missing service information"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:service-code nil})])))
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:service-name nil})]))))

  (testing "drops a rate missing currency"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:currency nil})])))
    (is (empty? (qe/filter-valid-rates [(dissoc (ts/raw-rate) :currency)]))))

  (testing "drops a rate with a missing total price"
    (is (empty? (qe/filter-valid-rates [(dissoc (ts/raw-rate) :total-amount)])))
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:total-amount nil})]))))

  (testing "drops a rate with an invalid (non-numeric) total price"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:total-amount "not-a-number"})]))))

  (testing "drops a rate with a negative total price"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:total-amount -1.00M})]))))

  (testing "drops a rate the provider explicitly marks unavailable"
    (is (empty? (qe/filter-valid-rates [(ts/raw-rate {:available? false})]))))

  (testing "AC5: one malformed rate does not invalidate the other valid rates returned in the same response"
    (let [good-1 (ts/raw-rate {:id "good-1"})
          bad    (ts/raw-rate {:id "bad" :total-amount -5.00M})
          good-2 (ts/raw-rate {:id "good-2" :service-name "Express"})
          result (qe/filter-valid-rates [good-1 bad good-2])]
      (is (= 2 (count result)))
      (is (= #{"good-1" "good-2"} (set (map :id result))))))

  (testing "all rates malformed yields an empty (not erroring) result"
    (is (= [] (qe/filter-valid-rates [(ts/raw-rate {:id nil})
                                       (ts/raw-rate {:currency nil})])))))

;; ---------------------------------------------------------------------------
;; 7.5 Quote ordering
;; ---------------------------------------------------------------------------
;; Each of these tests deliberately assigns :id values that would produce
;; the WRONG result if the implementation short-circuited on id instead of
;; the key under test -- isolating each of the 5 tie-break levels.

(deftest order-quotes-total-amount-test
  (testing "orders by total-amount ascending"
    (let [q-high (ts/normalized-quote {:id "high" :total-amount 20.00M})
          q-low  (ts/normalized-quote {:id "low" :total-amount 5.00M})
          q-mid  (ts/normalized-quote {:id "mid" :total-amount 12.50M})]
      (is (= ["low" "mid" "high"] (map :id (qe/order-quotes [q-high q-low q-mid])))))))

(deftest order-quotes-estimated-days-test
  (testing "same total-amount: orders by estimated-days ascending"
    (let [slower (ts/normalized-quote {:id "a-slower" :total-amount 10.00M :estimated-days 5})
          faster (ts/normalized-quote {:id "z-faster" :total-amount 10.00M :estimated-days 2})]
      (is (= ["z-faster" "a-slower"] (map :id (qe/order-quotes [slower faster])))))))

(deftest order-quotes-missing-estimated-days-test
  (testing "missing estimated-days sorts after known estimated-days at the same total-amount"
    (let [known   (ts/normalized-quote {:id "z-known" :total-amount 10.00M :estimated-days 3})
          unknown (ts/normalized-quote {:id "a-unknown" :total-amount 10.00M :estimated-days nil})]
      (is (= ["z-known" "a-unknown"] (map :id (qe/order-quotes [unknown known])))))))

(deftest order-quotes-carrier-test
  (testing "same total-amount and estimated-days: orders by carrier alphabetically"
    (let [acme   (ts/normalized-quote {:id "z-acme" :total-amount 10.00M :estimated-days 3 :carrier "Acme"})
          zenith (ts/normalized-quote {:id "a-zenith" :total-amount 10.00M :estimated-days 3 :carrier "Zenith"})]
      (is (= ["z-acme" "a-zenith"] (map :id (qe/order-quotes [zenith acme])))))))

(deftest order-quotes-service-name-test
  (testing "same total-amount, estimated-days, and carrier: orders by service-name alphabetically"
    (let [express  (ts/normalized-quote {:id "z-express" :total-amount 10.00M :estimated-days 3
                                          :carrier "Acme" :service-name "Express"})
          standard (ts/normalized-quote {:id "a-standard" :total-amount 10.00M :estimated-days 3
                                          :carrier "Acme" :service-name "Standard"})]
      (is (= ["z-express" "a-standard"] (map :id (qe/order-quotes [standard express])))))))

(deftest order-quotes-id-tiebreaker-test
  (testing "full tie on every other key: id is the final deterministic tie-breaker"
    (let [a (ts/normalized-quote {:id "a-id" :total-amount 10.00M :estimated-days 3
                                   :carrier "Acme" :service-name "Ground"})
          b (ts/normalized-quote {:id "b-id" :total-amount 10.00M :estimated-days 3
                                   :carrier "Acme" :service-name "Ground"})]
      (is (= ["a-id" "b-id"] (map :id (qe/order-quotes [b a])))))))

(deftest order-quotes-determinism-test
  (testing "the same input collection, presented in different orders, always sorts to the same result"
    (let [q1 (ts/normalized-quote {:id "q1" :total-amount 30.00M})
          q2 (ts/normalized-quote {:id "q2" :total-amount 10.00M})
          q3 (ts/normalized-quote {:id "q3" :total-amount 20.00M})
          q4 (ts/normalized-quote {:id "q4" :total-amount 5.00M})
          q5 (ts/normalized-quote {:id "q5" :total-amount 15.00M})
          expected ["q4" "q2" "q5" "q3" "q1"]
          permutation-a [q1 q2 q3 q4 q5]
          permutation-b [q5 q4 q3 q2 q1]
          permutation-c [q3 q1 q4 q5 q2]]
      (is (= expected (map :id (qe/order-quotes permutation-a))))
      (is (= expected (map :id (qe/order-quotes permutation-b))))
      (is (= expected (map :id (qe/order-quotes permutation-c))))
      (is (= (qe/order-quotes permutation-a)
             (qe/order-quotes permutation-b)
             (qe/order-quotes permutation-c))))))
