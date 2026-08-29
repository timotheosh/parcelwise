(ns parcelwise.startup.carrier-config-test
  "Behavioral tests for parcelwise.startup.carrier-config's pure
  `decide-provider` calculation -- spec section 8.2's fake path (Slice 2,
  'Default to fake when the value is absent') AND the shippo path (Slice
  4, 'If shippo is selected without SHIPPO_API_TOKEN, fail at application
  startup with a useful message that does not expose secrets').

  [Calculation test] -- `decide-provider` is documented in the approved
  plan as depending only on an explicit env map argument, returning an
  explicit value, doing no I/O -- directly testable without mount/env
  side effects.

  NOTE for the implementer: `parcelwise.startup.carrier-config-impl-test`
  (implementer-owned, from Slice 2) contains
  `shippo-provider-request-not-yet-honored-test`, which asserts
  `(decide-provider {:carrier-provider \"shippo\"})` still resolves to
  `:fake` -- that assertion directly contradicts the Slice 4 tests added
  below and was only ever documented as Slice-2-scope-limited behavior
  (see that file's own docstring). Since it's an implementer-owned file,
  the implementer may update or remove it directly as part of Slice 4;
  this is not an independent-test conflict requiring escalation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all]
    [parcelwise.startup.carrier-config :as carrier-config]))

(deftest defaults-to-fake-when-carrier-provider-is-absent-test
  (testing "spec 8.2: 'Default to fake when the value is absent in development and test environments'"
    (is (= :fake (:provider (carrier-config/decide-provider {}))))))

(deftest explicit-fake-provider-selected-test
  (is (= :fake (:provider (carrier-config/decide-provider {:carrier-provider "fake"})))))

;; ---------------------------------------------------------------------------
;; Slice 4 -- CARRIER_PROVIDER=shippo (spec 8.2)
;; ---------------------------------------------------------------------------

(deftest shippo-provider-with-valid-token-selected-test
  (testing "spec 8.2: CARRIER_PROVIDER=shippo with SHIPPO_API_TOKEN present selects the shippo provider and carries the token through"
    (is (= {:provider :shippo :token "test_abcdef123456"}
           (carrier-config/decide-provider {:carrier-provider "shippo"
                                             :shippo-api-token "test_abcdef123456"})))))

(deftest shippo-provider-without-token-fails-fast-test
  (testing "spec 8.2: 'If shippo is selected without SHIPPO_API_TOKEN, fail at application startup with a useful message that does not expose secrets'"
    (let [result (carrier-config/decide-provider {:carrier-provider "shippo"})]
      (is (contains? result :error) "must report an error decision, not silently fall back to :fake")
      (is (nil? (:provider result)) "an error decision must not also claim a usable provider")
      (is (string? (:error result)))
      (is (not (str/blank? (:error result))) "spec 8.2: the message must be useful, not empty")))

  (testing "an explicitly blank SHIPPO_API_TOKEN is treated the same as absent (spec 8.2's 'without SHIPPO_API_TOKEN')"
    (let [result (carrier-config/decide-provider {:carrier-provider "shippo" :shippo-api-token ""})]
      (is (contains? result :error))
      (is (nil? (:provider result))))))

(deftest shippo-fail-fast-error-does-not-expose-secrets-test
  (testing "spec 8.2/12: the fail-fast message must not expose secrets -- specifically, it must never contain 'SHIPPO_API_TOKEN=' followed by any value, since there is none to expose"
    (let [message (:error (carrier-config/decide-provider {:carrier-provider "shippo"}))]
      (is (string? message) "decide-provider must produce an :error string for this case -- see shippo-provider-without-token-fails-fast-test")
      (when (string? message)
        (is (not (str/includes? message "SHIPPO_API_TOKEN="))))))

  (testing "spec 12: the fail-fast message must not leak unrelated env-map content it happens to have been given"
    (let [message (:error (carrier-config/decide-provider {:carrier-provider "shippo"
                                                             :some-other-secret "super-sekrit-value-should-never-appear"
                                                             :database-url "postgres://user:hunter2@db.internal/prod"}))]
      (is (string? message) "decide-provider must produce an :error string for this case")
      (when (string? message)
        (is (not (str/includes? message "super-sekrit-value-should-never-appear")))
        (is (not (str/includes? message "hunter2")))))))

(deftest fake-provider-selection-unaffected-by-shippo-extension-test
  (testing "extending decide-provider to handle CARRIER_PROVIDER=shippo must not change the pre-existing fake-provider default/explicit-selection behavior (regression guard for Slice 2's already-approved behavior)"
    (is (= {:provider :fake} (carrier-config/decide-provider {})))
    (is (= {:provider :fake} (carrier-config/decide-provider {:carrier-provider "fake"})))))
