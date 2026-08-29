(ns parcelwise.startup.carrier-config
  "Decides which carrier provider the application should use, based on the
  `CARRIER_PROVIDER` environment value (spec section 8.2).

  Slice 4 extends Slice 2's `:fake`-only decision to also recognize
  `CARRIER_PROVIDER=shippo`, failing fast (a data decision, not the
  startup throw itself) with a secret-free message when `SHIPPO_API_TOKEN`
  is absent or blank."
  (:require
    [clojure.string :as str]))

(def ^:private missing-token-message
  "Fixed, static error message for the shippo-selected-without-token case
  (spec 8.2: 'fail at application startup with a useful message that does
  not expose secrets'). Deliberately never built by interpolating any
  part of the `env` map -- this is what makes 'never leaks secrets' true
  by construction rather than by convention."
  "CARRIER_PROVIDER is set to \"shippo\" but SHIPPO_API_TOKEN is not set. Set SHIPPO_API_TOKEN to a valid Shippo test-mode token to use the Shippo carrier provider.")

(defn decide-provider
  "[Calculation] Given the application's env map, decides which carrier
  provider to use. Depends only on the explicit `env` argument, returns an
  explicit value, does no I/O.

  - Absent or `\"fake\"` `:carrier-provider` -> `{:provider :fake}` (spec
    8.2's documented default).
  - `\"shippo\"` `:carrier-provider` with a non-blank `:shippo-api-token`
    -> `{:provider :shippo :token \"<the token>\"}`.
  - `\"shippo\"` `:carrier-provider` with a missing or blank
    `:shippo-api-token` -> `{:error \"<fixed, static, secret-free
    message>\"}` (no `:provider` key) -- spec 8.2's fail-fast
    requirement."
  [env]
  (let [provider (:carrier-provider env)]
    (if (= "shippo" provider)
      (let [token (:shippo-api-token env)]
        (if (str/blank? token)
          {:error missing-token-message}
          {:provider :shippo :token token}))
      {:provider :fake})))
