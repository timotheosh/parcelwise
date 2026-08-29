(ns parcelwise.carrier.shippo.adapter-impl-test
  "Implementer-owned coverage for parcelwise.carrier.shippo.adapter,
  filling gaps `.ai/test-plan.md`'s Slice 4 section explicitly flags as
  intentionally uncovered by the independent suite under its time budget:

  1. A real socket timeout -- proving `client.clj`/`adapter.clj` actually
     detects a genuine `HttpTimeoutException` (a slow-responding local
     stub combined with a short configured `request-timeout-ms`), not
     just that `mapping/classify-failure` maps `{:timeout? true}`
     correctly in isolation. Flagged in the test plan as 'the one path in
     spec 8.2's failure list that cannot be fully verified by the
     classification-layer tests alone.'
  2. A connection failure (nothing listening on the target port) ->
     `:upstream-error`, exercising `client.clj`'s IOException branch,
     which no independent test reaches.

  Both stubs/targets are loopback-only (127.0.0.1); neither test touches
  the real network. Does not touch or weaken the independent suite."
  (:require
    [clojure.test :refer :all]
    [parcelwise.carrier.shippo.adapter :as adapter]
    [parcelwise.test-support :as ts])
  (:import
    [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
    [java.net InetSocketAddress ServerSocket]))

(defn- start-slow-stub-server!
  "Starts a local HTTP server bound to 127.0.0.1 on an ephemeral port that
  sleeps for `delay-ms` before responding with a trivial 200 JSON body --
  used to reliably exceed a short configured request-timeout-ms."
  [delay-ms]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (Thread/sleep ^long delay-ms)
          (let [body (.getBytes "{\"object_id\":\"x\",\"rates\":[]}" "UTF-8")]
            (.sendResponseHeaders ^HttpExchange exchange 200 (count body))
            (with-open [os (.getResponseBody ^HttpExchange exchange)]
              (.write os body))))))
    (.setExecutor server nil)
    (.start server)
    server))

(defn- server-base-url [^HttpServer server]
  (str "http://127.0.0.1:" (.getPort (.getAddress server))))

(defn- stop-stub-server! [^HttpServer server]
  (.stop server 0))

(defn- unused-loopback-port
  "Finds an ephemeral loopback port with nothing listening on it, by
  briefly binding then immediately closing a socket -- deterministic
  'connection refused' target for the connection-failure test."
  []
  (with-open [socket (ServerSocket. 0 0 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort socket)))

(deftest shippo-carrier-real-socket-timeout-test
  (testing "a request-timeout-ms shorter than the stub's real response delay is actually detected as a timeout over a real socket, not merely classified in isolation"
    (let [server (start-slow-stub-server! 500)]
      (try
        (let [carrier (adapter/shippo-carrier {:token               "test_dummy_token"
                                                :base-url            (server-base-url server)
                                                :connect-timeout-ms  2000
                                                :request-timeout-ms  50})
              result (ts/assert-carrier-contract carrier (ts/quote-request))]
          (is (= :error (:status result)))
          (is (= :timeout (:error-type result))))
        (finally
          (stop-stub-server! server))))))

(deftest shippo-carrier-connection-failure-maps-to-upstream-error-test
  (testing "a connection failure (nothing listening on the target port) is classified as :upstream-error, not an uncaught exception"
    (let [port (unused-loopback-port)
          carrier (adapter/shippo-carrier {:token               "test_dummy_token"
                                            :base-url            (str "http://127.0.0.1:" port)
                                            :connect-timeout-ms  1000
                                            :request-timeout-ms  2000})
          result (ts/assert-carrier-contract carrier (ts/quote-request))]
      (is (= :error (:status result)))
      (is (= :upstream-error (:error-type result))))))
