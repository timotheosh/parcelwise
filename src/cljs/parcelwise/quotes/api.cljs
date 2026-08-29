(ns parcelwise.quotes.api
  "[Action] -- the thin HTTP boundary for `POST /api/quotes` (spec 9.1).

  Uses `ajax.core`'s plain JSON support directly (`:format :json`,
  `:response-format :json`), **not** `parcelwise.ajax`'s transit
  interceptor stack -- that machinery exists for the generated scaffold's
  own transit endpoints, whereas Slice 2's `/api/quotes` contract is plain
  camelCase JSON (spec 9.1).

  This namespace performs no business logic of its own: it delegates
  request construction and response decoding entirely to
  `parcelwise.quotes.logic`'s pure functions, and applies the resulting
  state directly to the shared `parcelwise.quotes.state/state` atom. The
  only thing that happens here is the actual I/O call and the mutation
  it drives."
  (:require
    [ajax.core :refer [POST]]
    [parcelwise.quotes.logic :as logic]
    [parcelwise.quotes.state :as state]))

(defn submit-quote-request!
  "Submits `form` (the `:form` sub-map of `state`'s shape) to
  `POST /api/quotes`. Immediately transitions `state` to the `:loading`
  phase (AC8), then applies `logic/decode-success` or `logic/decode-error`
  to whatever the server returns."
  [form]
  (swap! state/state logic/start-loading)
  (POST "/api/quotes"
    {:params          (logic/build-request form)
     :format          :json
     :response-format :json
     :keywords?       true
     :handler         (fn [response-body]
                         (swap! state/state logic/decode-success response-body))
     :error-handler   (fn [{:keys [status response]}]
                         (swap! state/state logic/decode-error status response))}))
