(ns parcelwise.middleware.body-size-limit-test
  "Direct, middleware-function-level tests for
  parcelwise.middleware/wrap-body-size-limit -- spec section 12's 'apply
  reasonable request-body size limits' requirement.

  API contract fixed by this test file: `(wrap-body-size-limit handler
  max-bytes)` wraps a ring handler. It must enforce the limit against
  *actual bytes read from the request body*, not merely a `Content-Length`
  header check -- a header-only check is bypassable, since `Content-Length`
  is absent under chunked transfer-encoding and, even when present, is
  merely a client-supplied claim rather than something a naive
  'reject if header > N' check verifies against the real stream. When the
  limit is exceeded, the wrapped handler must not be invoked and a 413
  response must be returned. When the body is within the limit, the
  wrapped handler must be invoked and must still be able to read the full,
  unmodified body.

  Tested here directly against synthetic ring request maps (bypassing
  ring-mock entirely), per the approved plan's own note that ring-mock may
  not easily simulate a body that lies about its own length -- exactly the
  scenario this middleware exists to defend against. A full HTTP
  round-trip sanity check (oversized body -> 413) also exists at
  parcelwise.routes.quotes-route-test/oversized-request-body-is-rejected-test."
  (:require
    [clojure.test :refer :all]
    [parcelwise.middleware :as middleware])
  (:import
    (java.io ByteArrayInputStream)))

(def ^:private max-bytes 1000)

(defn- body-stream [byte-count]
  (ByteArrayInputStream. (byte-array byte-count (byte \x))))

(defn- throwing-handler [_request]
  (throw (AssertionError. "the wrapped handler must not be invoked when the body-size limit is exceeded")))

(deftest body-within-limit-invokes-handler-and-preserves-full-content-test
  (testing "a body at/under the limit is passed through unmodified and the handler is invoked"
    (let [handler (fn [request] {:status 200 :body (slurp (:body request))})
          wrapped (middleware/wrap-body-size-limit handler max-bytes)
          response (wrapped {:body (body-stream 500) :headers {}})]
      (is (= 200 (:status response)))
      (is (= 500 (count (:body response)))))))

(deftest body-exceeding-limit-with-no-content-length-header-rejected-test
  (testing "actual bytes are counted even when Content-Length is entirely absent (simulating chunked transfer-encoding)"
    (let [wrapped (middleware/wrap-body-size-limit throwing-handler max-bytes)
          response (wrapped {:body (body-stream (+ max-bytes 1)) :headers {}})]
      (is (= 413 (:status response))))))

(deftest body-exceeding-limit-with-lying-content-length-header-still-rejected-test
  (testing "a Content-Length header that understates the real body size does not bypass the limit -- actual bytes read are what's enforced, not the claimed header"
    (let [wrapped (middleware/wrap-body-size-limit throwing-handler max-bytes)
          response (wrapped {:body    (body-stream (+ max-bytes 1))
                              :headers {"content-length" "10"}})]
      (is (= 413 (:status response))))))

(deftest handler-not-invoked-when-limit-exceeded-test
  (testing "the wrapped handler is never called once the limit is exceeded (would otherwise throw via throwing-handler)"
    (let [wrapped (middleware/wrap-body-size-limit throwing-handler max-bytes)]
      (is (= 413 (:status (wrapped {:body (body-stream (* max-bytes 10)) :headers {}})))))))

(deftest body-exactly-at-limit-is-accepted-test
  (testing "a body of exactly max-bytes is within the limit, not rejected (boundary case)"
    (let [handler (fn [request] {:status 200 :body (slurp (:body request))})
          wrapped (middleware/wrap-body-size-limit handler max-bytes)
          response (wrapped {:body (body-stream max-bytes) :headers {}})]
      (is (= 200 (:status response)))
      (is (= max-bytes (count (:body response)))))))
