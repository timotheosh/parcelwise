(ns parcelwise.routes.services
  (:require
    [reitit.swagger :as swagger]
    [reitit.swagger-ui :as swagger-ui]
    [reitit.ring.coercion :as coercion]
    [reitit.coercion.spec :as spec-coercion]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.multipart :as multipart]
    [reitit.ring.middleware.parameters :as parameters]
    [parcelwise.carrier.fake :as fake]
    [parcelwise.carrier.shippo.adapter :as shippo-adapter]
    [parcelwise.config :refer [env]]
    [parcelwise.http.api-spec :as api-spec]
    [parcelwise.http.quotes-handler :as quotes-handler]
    [parcelwise.middleware :as middleware]
    [parcelwise.middleware.formats :as formats]
    [parcelwise.startup.carrier-config :as carrier-config]
    [ring.util.http-response :refer :all]
    [clojure.java.io :as io]))

(def ^:private quotes-body-size-limit-bytes
  "Request-body size limit for POST /api/quotes (spec section 12)."
  (* 1024 1024))

(def ^:private shippo-base-url
  "The real Shippo REST API base URL (spec 8.2: 'Use the Shippo REST
  API'). Not independently configurable -- spec 8.2 does not require it,
  and the adapter itself already accepts an injectable base-url for
  test-mode/stub wiring (see adapter_test.clj's loopback-server usage)."
  "https://api.goshippo.com")

(def ^:private shippo-connect-timeout-ms
  "Explicit connection timeout for the real Shippo adapter (spec 8.2:
  'Use explicit connection and request timeouts')."
  5000)

(def ^:private shippo-request-timeout-ms
  "Explicit request timeout for the real Shippo adapter (spec 8.2: 'Use
  explicit connection and request timeouts')."
  10000)

(defn- carrier-adapter-for-provider
  "[Action-supporting] Constructs the concrete CarrierAdapter for the
  given provider-decision result (parcelwise.startup.carrier-config/decide-provider's
  return value). Consumes that decision faithfully -- the provider-selection
  business rule itself lives entirely in decide-provider, a pure
  calculation; this function only performs the corresponding I/O-adjacent
  construction/failure."
  [provider-decision]
  (if-let [error-message (:error provider-decision)]
    (throw (IllegalStateException. ^String error-message))
    (case (:provider provider-decision)
      :fake (fake/fake-carrier :normal)
      :shippo (shippo-adapter/shippo-carrier
                {:token               (:token provider-decision)
                 :base-url            shippo-base-url
                 :connect-timeout-ms  shippo-connect-timeout-ms
                 :request-timeout-ms  shippo-request-timeout-ms}))))

(defn service-routes []
  (let [provider-decision (carrier-config/decide-provider env)
        provider (:provider provider-decision)
        carrier-adapter (carrier-adapter-for-provider provider-decision)]
    ["/api"
     {:coercion spec-coercion/coercion
      :muuntaja formats/instance
      :swagger {:id ::api}
      :middleware [;; query-params & form-params
                   parameters/parameters-middleware
                   ;; content-negotiation
                   muuntaja/format-negotiate-middleware
                   ;; encoding response body
                   muuntaja/format-response-middleware
                   ;; exception handling
                   coercion/coerce-exceptions-middleware
                   ;; decoding request body
                   muuntaja/format-request-middleware
                   ;; coercing response bodys
                   coercion/coerce-response-middleware
                   ;; coercing request parameters
                   coercion/coerce-request-middleware
                   ;; multipart
                   multipart/multipart-middleware]}

   ;; swagger documentation
   ["" {:no-doc true
        :swagger {:info {:title "my-api"
                         :description "https://cljdoc.org/d/metosin/reitit"}}}

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
             {:url "/api/swagger.json"
              :config {:validator-url nil}})}]]

   ["/ping"
    {:get (constantly (ok {:message "pong"}))}]


   ["/quotes"
    {:coercion nil
     :swagger {:tags ["quotes"]}
     :middleware ^:prepend [(fn [handler] (middleware/wrap-body-size-limit handler quotes-body-size-limit-bytes))]
     :post (merge {:handler (quotes-handler/handler carrier-adapter provider)}
                   api-spec/quotes-route-data)}]

   ["/math"
    {:swagger {:tags ["math"]}}

    ["/plus"
     {:get {:summary "plus with spec query parameters"
            :parameters {:query {:x int?, :y int?}}
            :responses {200 {:body {:total pos-int?}}}
            :handler (fn [{{{:keys [x y]} :query} :parameters}]
                       {:status 200
                        :body {:total (+ x y)}})}
      :post {:summary "plus with spec body parameters"
             :parameters {:body {:x int?, :y int?}}
             :responses {200 {:body {:total pos-int?}}}
             :handler (fn [{{{:keys [x y]} :body} :parameters}]
                        {:status 200
                         :body {:total (+ x y)}})}}]]

   ["/files"
    {:swagger {:tags ["files"]}}

    ["/upload"
     {:post {:summary "upload a file"
             :parameters {:multipart {:file multipart/temp-file-part}}
             :responses {200 {:body {:name string?, :size int?}}}
             :handler (fn [{{{:keys [file]} :multipart} :parameters}]
                        {:status 200
                         :body {:name (:filename file)
                                :size (:size file)}})}}]

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :handler (fn [_]
                       {:status 200
                        :headers {"Content-Type" "image/png"}
                        :body (-> "public/img/warning_clojure.png"
                                  (io/resource)
                                  (io/input-stream))})}}]]]))
