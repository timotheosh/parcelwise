(ns parcelwise.http.quotes-handler-impl-test
  "Implementer-owned unit test covering the 2-arity
  (handler carrier-adapter provider) form of
  parcelwise.http.quotes-handler/handler -- an additive arity beyond the
  1-arity form fixed by the independent test suite's documented public-API
  contract (quotes_handler_test.clj only exercises the 1-arity default,
  which stamps :fake). Added so the real, route-wiring-used
  provider-stamping path (parcelwise.routes.services calls the 2-arity
  form with whatever parcelwise.startup.carrier-config decides) is
  actually observed working, not just assumed identical to the default.

  Builds its own minimal reitit-ring app inline rather than extending the
  shared, test-designer-owned parcelwise.http.test-support harness (which
  only exposes the 1-arity form) -- keeps this implementer-owned coverage
  self-contained and avoids touching independent shared test
  infrastructure. Does not touch or weaken the independent suite."
  (:require
    [clojure.test :refer :all]
    [muuntaja.core :as m]
    [parcelwise.carrier.fake :as fake]
    [parcelwise.http.quotes-handler :as quotes-handler]
    [parcelwise.middleware.formats :as formats]
    [parcelwise.test-support :as ts]
    [reitit.ring :as ring]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [ring.mock.request :refer :all]))

(defn- test-app [carrier-adapter provider]
  (ring/ring-handler
    (ring/router
      ["/api/quotes"
       {:post {:handler (quotes-handler/handler carrier-adapter provider)}}]
      {:data {:muuntaja formats/instance
              :middleware [muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware]}})))

(defn- decode [response]
  (m/decode formats/instance "application/json" (:body response)))

(deftest explicit-provider-argument-is-stamped-onto-every-quote-test
  (testing "the 2-arity handler stamps the given provider, not just the 1-arity default of :fake"
    (let [app (test-app (fake/fake-carrier :normal) :some-other-provider)
          response (app (-> (request :post "/api/quotes") (json-body (ts/json-quote-request))))
          body (decode response)]
      (is (= 200 (:status response)))
      (is (seq (:quotes body)))
      (is (every? #(= "some-other-provider" (:provider %)) (:quotes body))))))
