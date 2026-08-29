(ns parcelwise.carrier.shippo.client
  "The actual `java.net.http.HttpClient` call to Shippo's rates endpoint
  (spec section 8.2).

  [Action] -- performs real network I/O, depends on explicit connect and
  request timeouts (never hardcoded/defaulted here -- spec 8.2: 'Use
  explicit connection and request timeouts'). Delegates all outcome
  classification (which HTTP status or exception maps to which stable
  application error-type) to the pure `parcelwise.carrier.shippo.mapping/classify-failure`
  calculation -- this namespace only decides *what happened* (a JSON body,
  or a failure descriptor), never *what it means*."
  (:require
    [jsonista.core :as json]
    [parcelwise.carrier.shippo.mapping :as mapping])
  (:import
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                    HttpResponse HttpResponse$BodyHandlers]
    [java.net URI]
    [java.time Duration]
    [java.io IOException]
    [java.net.http HttpTimeoutException]))

(defn- build-client
  ^HttpClient [connect-timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
      (.build)))

(defn- build-request
  ^HttpRequest [{:keys [base-url token request-timeout-ms]} shippo-request-body]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create (str base-url "/shipments/")))
      (.timeout (Duration/ofMillis request-timeout-ms))
      (.header "Content-Type" "application/json")
      (.header "Authorization" (str "ShippoToken " token))
      (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string shippo-request-body)))
      (.build)))

(defn fetch-shipment-rates
  "[Action] Performs the real HTTP POST to Shippo's shipment-rates
  endpoint (`{base-url}/shipments/`) and returns either:
  - `{:outcome :ok :body <parsed-Clojure-map>}` on a 2xx response, or
  - `{:outcome :error :error-type <kind>}` on any other outcome (a
    non-2xx status, a timeout, or a connection failure), with `<kind>`
    produced by `parcelwise.carrier.shippo.mapping/classify-failure` --
    never a raw exception, raw status code, or raw response body.

  `config` is `{:keys [token base-url connect-timeout-ms
  request-timeout-ms]}`, all explicit and injectable -- never read from
  environment/config state directly by this namespace. Never logs the
  token or the full request/response body."
  [{:keys [connect-timeout-ms] :as config} shippo-request-body]
  (try
    (let [client (build-client connect-timeout-ms)
          request (build-request config shippo-request-body)
          ^HttpResponse response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        {:outcome :ok :body (json/read-value (.body response) json/keyword-keys-object-mapper)}
        {:outcome :error :error-type (mapping/classify-failure {:status status})}))
    (catch HttpTimeoutException _
      {:outcome :error :error-type (mapping/classify-failure {:timeout? true})})
    (catch IOException _
      {:outcome :error :error-type (mapping/classify-failure {:status nil})})
    (catch Exception _
      {:outcome :error :error-type (mapping/classify-failure {:status nil})})))
