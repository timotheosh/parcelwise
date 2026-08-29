(ns parcelwise.startup.carrier-config-impl-test
  "Implementer-owned unit tests for parcelwise.startup.carrier-config/decide-provider.

  Slice 4 update: the Slice-2-era `shippo-provider-request-not-yet-honored-test`
  asserted the opposite of Slice 4's now-implemented behavior (that
  CARRIER_PROVIDER=shippo did not yet select the Shippo adapter) -- that
  assertion is removed here since it now describes obsolete, no-longer-true
  scope, per the independent `carrier_config_test.clj`'s own docstring note
  flagging this as an implementer-owned (not independent-test) conflict.
  Replaced with implementer-owned coverage for a case the independent
  Slice 4 suite does not otherwise exercise: a `:carrier-provider` value
  that is neither \"fake\" nor \"shippo\"."
  (:require
    [clojure.test :refer :all]
    [parcelwise.startup.carrier-config :as carrier-config]))

(deftest unrecognized-provider-value-defaults-to-fake-test
  (testing "an unrecognized :carrier-provider value falls back to :fake rather than erroring or throwing (only \"shippo\" is treated as an explicit non-fake request)"
    (is (= {:provider :fake}
           (carrier-config/decide-provider {:carrier-provider "not-a-real-provider"})))))
