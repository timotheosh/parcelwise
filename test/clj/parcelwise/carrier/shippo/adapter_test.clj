(ns parcelwise.carrier.shippo.adapter-test
  "Slice 4 behavioral tests for parcelwise.carrier.shippo.adapter (spec
  8.2). [Action tests] -- unlike mapping-test, these tests do exercise real
  HTTP I/O, but ONLY against a local, loopback (127.0.0.1) HTTP stub server
  started in-process for the duration of each test -- never against
  api.goshippo.com or any other real network endpoint (spec 13.3/16: 'No
  normal automated test requires network access'; 'Do not make live Shippo
  calls in the normal automated test suite').

  Per the task's explicit guidance, deep client/adapter network-level
  coverage is intentionally kept modest here (happy path + one failure
  path + the token-non-leak check) relative to the more exhaustive,
  zero-I/O mapping-test.clj coverage -- see .ai/test-plan.md's Slice 4
  'Intentionally uncovered behavior' section for what is deliberately not
  covered by an automated test in this pass (timeout simulation,
  rate-limit/upstream-error over the wire, and captured-log-output proof
  that the token is never logged)."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [parcelwise.carrier.protocol :as protocol]
    [parcelwise.carrier.shippo.adapter :as adapter]
    [parcelwise.carrier.shippo.fixtures :as fixtures]
    [parcelwise.specs :as specs]
    [parcelwise.test-support :as ts])
  (:import
    [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
    [java.net InetSocketAddress]))

(defn- start-stub-server!
  "Starts a local HTTP server bound to 127.0.0.1 on an ephemeral port that
  responds to every request with `status` and `body` (a JSON string),
  recording the most recently received request's Authorization header
  into `captured-auth` (an atom). Loopback only -- never reaches the real
  network."
  [status body captured-auth]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (reset! captured-auth (.getFirst (.getRequestHeaders ^HttpExchange exchange) "Authorization"))
          (let [response-bytes (.getBytes ^String body "UTF-8")]
            (.sendResponseHeaders ^HttpExchange exchange status (count response-bytes))
            (with-open [os (.getResponseBody ^HttpExchange exchange)]
              (.write os response-bytes))))))
    (.setExecutor server nil)
    (.start server)
    server))

(defn- server-base-url [^HttpServer server]
  (str "http://127.0.0.1:" (.getPort (.getAddress server))))

(defn- stop-stub-server! [^HttpServer server]
  (.stop server 0))

(deftest shippo-carrier-satisfies-protocol-test
  (testing "adapter contract (spec 13.4): shippo-carrier implements the same CarrierAdapter protocol the fake adapter implements -- no network call needed for this check"
    (is (satisfies? protocol/CarrierAdapter
                     (adapter/shippo-carrier {:token               "test_dummy_token"
                                               :base-url            "https://api.goshippo.com"
                                               :connect-timeout-ms  5000
                                               :request-timeout-ms  10000})))))

(deftest shippo-carrier-happy-path-test
  (testing "spec 8.2/16: retrieves and normalizes test rates with valid credentials, against a local stub standing in for Shippo's test API"
    (let [captured-auth (atom nil)
          server (start-stub-server! 200 fixtures/shippo-response-json captured-auth)]
      (try
        (let [carrier (adapter/shippo-carrier {:token               "test_dummy_token_123"
                                                :base-url            (server-base-url server)
                                                :connect-timeout-ms  2000
                                                :request-timeout-ms  5000})
              result (ts/assert-carrier-contract carrier (ts/quote-request))]

          (testing "returns :ok with the Shippo response normalized into raw rates"
            (is (= :ok (:status result)))
            (is (= 3 (count (:rates result))))
            (is (= #{"rate_usps_priority_001" "rate_usps_ground_002" "rate_fedex_overnight_003"}
                   (set (map :id (:rates result))))))

          (testing "authenticates using the configured token (spec 8.2: 'Read the token from SHIPPO_API_TOKEN')"
            (is (= "ShippoToken test_dummy_token_123" @captured-auth)))

          (testing "never leaks the token into the returned carrier-result (spec 12: 'Never send the token to the browser')"
            (is (not (str/includes? (pr-str result) "test_dummy_token_123")))))
        (finally
          (stop-stub-server! server))))))

(deftest shippo-carrier-auth-failure-test
  (testing "spec 8.2: maps a Shippo authentication failure (HTTP 401) into the stable, granular :auth-failure application error"
    (let [captured-auth (atom nil)
          server (start-stub-server! 401 "{\"detail\":\"Invalid token\"}" captured-auth)]
      (try
        (let [carrier (adapter/shippo-carrier {:token               "test_bad_token"
                                                :base-url            (server-base-url server)
                                                :connect-timeout-ms  2000
                                                :request-timeout-ms  5000})
              result (ts/assert-carrier-contract carrier (ts/quote-request))]
          (is (= :error (:status result)))
          (is (= :auth-failure (:error-type result)))
          (testing "raw provider error details are not exposed past the adapter boundary (spec 9.3: 'Do not expose raw provider responses')"
            (is (not (str/includes? (pr-str result) "Invalid token")))))
        (finally
          (stop-stub-server! server))))))

(deftest shippo-carrier-rate-limited-test
  (testing "spec 8.2: maps a Shippo rate-limit response (HTTP 429) into the stable, granular :rate-limited application error"
    (let [captured-auth (atom nil)
          server (start-stub-server! 429 "{\"detail\":\"Too many requests\"}" captured-auth)]
      (try
        (let [carrier (adapter/shippo-carrier {:token               "test_dummy_token"
                                                :base-url            (server-base-url server)
                                                :connect-timeout-ms  2000
                                                :request-timeout-ms  5000})
              result (ts/assert-carrier-contract carrier (ts/quote-request))]
          (is (= :error (:status result)))
          (is (= :rate-limited (:error-type result))))
        (finally
          (stop-stub-server! server))))))
