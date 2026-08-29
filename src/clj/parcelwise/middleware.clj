(ns parcelwise.middleware
  (:require
    [parcelwise.env :refer [defaults]]
    [clojure.tools.logging :as log]
    [parcelwise.layout :refer [error-page]]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [parcelwise.middleware.formats :as formats]
    [muuntaja.core :as muuntaja]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [parcelwise.config :refer [env]]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.adapter.undertow.middleware.session :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults]])
  (:import
    (java.io ByteArrayInputStream InputStream)))

(defn wrap-internal-error [handler]
  (let [error-result (fn [^Throwable t]
                       (log/error t (.getMessage t))
                       (error-page {:status 500
                                    :title "Something very bad has happened!"
                                    :message "We've dispatched a team of highly trained gnomes to take care of the problem."}))]
    (fn wrap-internal-error-fn
      ([req respond _]
       (handler req respond #(respond (error-result %))))
      ([req]
       (try
         (handler req)
         (catch Throwable t
           (error-result t)))))))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-page
       {:status 403
        :title "Invalid anti-forgery token"})}))


(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format formats/instance))]
    (fn
      ([request]
         ;; disable wrap-formats for websockets
         ;; since they're not compatible with this middleware
       ((if (:websocket? request) handler wrapped) request))
      ([request respond raise]
       ((if (:websocket? request) handler wrapped) request respond raise)))))

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      wrap-flash
      (wrap-session {:cookie-attrs {:http-only true}})
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      wrap-internal-error))

(defn- read-bounded-bytes
  "Reads at most `max-bytes` + 1 bytes from `body` (an InputStream).
  Reading one byte past the limit is what lets the caller distinguish 'the
  body is exactly max-bytes' from 'the body exceeds max-bytes' without
  needing to know the body's total length up front."
  ^bytes [^InputStream body max-bytes]
  (.readNBytes body (inc max-bytes)))

(defn wrap-body-size-limit
  "Wraps `handler`, rejecting any request whose body exceeds `max-bytes`.
  Enforces the limit against *actual bytes read* from `:body` (spec
  section 12) -- not merely a `Content-Length` header, which is absent
  under chunked transfer-encoding and, even when present, is only a
  client-supplied claim rather than something a naive
  'reject if header > max-bytes' check verifies against the real stream.

  When the limit is exceeded, `handler` is never invoked and a 413
  response is returned. When the body is within the limit, `handler`
  receives the request with `:body` replaced by a fresh stream over the
  exact, full, unmodified bytes read (the original stream having already
  been consumed while measuring it)."
  [handler max-bytes]
  (letfn [(within-limit [request]
            (let [bytes (read-bounded-bytes (:body request) max-bytes)]
              (when (<= (alength ^bytes bytes) max-bytes)
                (assoc request :body (ByteArrayInputStream. bytes)))))
          (too-large-response []
            ;; This response is returned outside the /api route group's
            ;; `format-response-middleware` (wrap-body-size-limit runs
            ;; before request-body parsing, so it must short-circuit
            ;; ahead of response-format negotiation too). Because no
            ;; downstream middleware will JSON-encode it, the body is
            ;; encoded here directly -- reusing the same muuntaja
            ;; instance/codec (`parcelwise.middleware.formats/instance`)
            ;; the rest of the app uses -- so a real HTTP adapter (e.g.
            ;; Undertow) can actually write the response body instead of
            ;; throwing on an un-encoded Clojure map.
            {:status 413
             :headers {"Content-Type" "application/json; charset=utf-8"}
             :body (muuntaja/encode formats/instance "application/json"
                     {:error {:code    "request-too-large"
                              :message "The request body exceeds the maximum allowed size."}})})]
    (fn wrap-body-size-limit-fn
      ([request]
       (if-let [bounded-request (within-limit request)]
         (handler bounded-request)
         (too-large-response)))
      ([request respond raise]
       (if-let [bounded-request (within-limit request)]
         (handler bounded-request respond raise)
         (respond (too-large-response)))))))


