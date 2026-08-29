(ns parcelwise.middleware.body-size-limit-impl-test
  "Implementer-owned unit tests covering the async (3-arity,
  respond/raise) calling convention of
  parcelwise.middleware/wrap-body-size-limit, which the independent
  behavioral suite (body_size_limit_test.clj) does not exercise (it only
  tests the synchronous 1-arity form, matching how this middleware is
  actually invoked by the rest of the application). Added for symmetry
  with this same namespace's other middleware (wrap-formats,
  wrap-internal-error), which also support both calling conventions, and
  so this code path is not left silently untested. Does not touch or
  weaken the independent suite."
  (:require
    [clojure.test :refer :all]
    [muuntaja.core :as muuntaja]
    [parcelwise.middleware :as middleware]
    [parcelwise.middleware.formats :as formats])
  (:import
    (java.io ByteArrayInputStream)))

(defn- decode-json-body
  "Decodes a response `:body` (an InputStream/bytes/String produced by
  muuntaja's JSON encoder) back into Clojure data, using the exact same
  muuntaja instance the production code encodes with -- proving the body
  is genuinely valid, well-formed JSON rather than a raw Clojure map that
  merely happens to look like one when printed."
  [body]
  (muuntaja/decode formats/instance "application/json" body))

(def ^:private max-bytes 1000)

(defn- body-stream [byte-count]
  (ByteArrayInputStream. (byte-array byte-count (byte \x))))

(deftest async-within-limit-invokes-handler-and-responds-test
  (testing "the 3-arity form invokes handler, which calls respond, when the body is within the limit"
    (let [handler (fn [request respond _raise] (respond {:status 200 :body (slurp (:body request))}))
          wrapped (middleware/wrap-body-size-limit handler max-bytes)
          result (promise)]
      (wrapped {:body (body-stream 500) :headers {}} #(deliver result %) #(deliver result %))
      (is (= 200 (:status @result)))
      (is (= 500 (count (:body @result)))))))

(deftest async-exceeding-limit-responds-413-without-invoking-handler-test
  (testing "the 3-arity form calls respond with a 413 and never invokes handler when the limit is exceeded"
    (let [handler (fn [_request _respond _raise]
                     (throw (AssertionError. "handler must not be invoked when the body-size limit is exceeded")))
          wrapped (middleware/wrap-body-size-limit handler max-bytes)
          result (promise)]
      (wrapped {:body (body-stream (+ max-bytes 1)) :headers {}} #(deliver result %) #(deliver result %))
      (is (= 413 (:status @result))))))

;; CORR-S2-1 regression coverage: the 413 response is returned as the
;; OUTERMOST middleware on /api/quotes (see routes/services.clj's
;; `^:prepend` wiring), meaning it is returned to the ring adapter
;; without ever passing through the /api route group's
;; `muuntaja/format-response-middleware`. A raw Clojure map `:body`
;; therefore reaches a real HTTP adapter (e.g. Undertow) unencoded and
;; crashes it (`UnsupportedOperationException: Body class not
;; supported: class clojure.lang.PersistentArrayMap`) instead of
;; delivering a 413 to the client. ring-mock (used by the independent
;; body_size_limit_test.clj and quotes_route_test.clj suites) never
;; actually serializes the response body through a real adapter, so it
;; cannot catch this class of defect -- these tests instead assert on
;; the response shape itself (JSON-encoded body string + a JSON
;; Content-Type header), which is the part `lein test` *can* verify;
;; the real-adapter round trip was additionally verified manually
;; against a live Undertow server (see .ai/implementation-report.md).
(deftest too-large-response-body-is-valid-encoded-json-test
  (testing "the 413 response body is bytes/a stream produced by the app's own JSON encoder (muuntaja formats/instance), not a raw Clojure map -- so a real (non-mock) HTTP adapter can actually write it instead of throwing UnsupportedOperationException"
    (let [wrapped (middleware/wrap-body-size-limit (fn [_] (throw (AssertionError. "handler must not be invoked"))) max-bytes)
          response (wrapped {:body (body-stream (+ max-bytes 1)) :headers {}})
          decoded (decode-json-body (:body response))]
      (is (not (map? (:body response)))
          "the raw :body value must not be a bare Clojure map -- ring-undertow-adapter's RespondBody protocol has no implementation for PersistentArrayMap and would throw UnsupportedOperationException when writing the response")
      (is (= {:error {:code "request-too-large"
                       :message "The request body exceeds the maximum allowed size."}}
             decoded)
          "decoding the encoded body through the same muuntaja instance the app encodes with must round-trip to the original error payload"))))

(deftest too-large-response-content-type-header-is-json-test
  (testing "the 413 response declares a JSON Content-Type header, matching the JSON-encoded body"
    (let [wrapped (middleware/wrap-body-size-limit (fn [_] (throw (AssertionError. "handler must not be invoked"))) max-bytes)
          response (wrapped {:body (body-stream (+ max-bytes 1)) :headers {}})]
      (is (re-find #"^application/json" (get-in response [:headers "Content-Type"] ""))))))

(deftest async-too-large-response-body-is-valid-encoded-json-test
  (testing "the 3-arity (respond/raise) form's 413 response body is also JSON-encoded, not a raw map, and declares a JSON Content-Type header"
    (let [wrapped (middleware/wrap-body-size-limit
                    (fn [_request _respond _raise]
                      (throw (AssertionError. "handler must not be invoked")))
                    max-bytes)
          result (promise)]
      (wrapped {:body (body-stream (+ max-bytes 1)) :headers {}} #(deliver result %) #(deliver result %))
      (is (not (map? (:body @result))))
      (is (re-find #"^application/json" (get-in @result [:headers "Content-Type"] "")))
      (is (= {:error {:code "request-too-large"
                       :message "The request body exceeds the maximum allowed size."}}
             (decode-json-body (:body @result)))))))
