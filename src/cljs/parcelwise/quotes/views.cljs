(ns parcelwise.quotes.views
  "[Action] -- the Reagent quote form UI (spec section 10). Reads
  `parcelwise.quotes.state/state` and delegates every request-building,
  response-decoding, and field-error-key derivation to
  `parcelwise.quotes.logic`/`parcelwise.quotes.api`; no business logic
  lives inline in component code here."
  (:require
    [clojure.string :as str]
    [parcelwise.quotes.api :as api]
    [parcelwise.quotes.logic :as logic]
    [parcelwise.quotes.state :as state]))

;; ---------------------------------------------------------------------------
;; Accessibility: move focus to the error summary after a failed submission
;; (spec 10.6, AC9). `logic/decode-error` sets `:focus-error-summary?` on the
;; state map (a pure calculation, independently tested); this watcher is the
;; one place that DOM side effect actually happens.
;; ---------------------------------------------------------------------------

(defn- focus-error-summary! []
  (when-let [el (.getElementById js/document "error-summary")]
    (.focus el)))

(defonce ^:private focus-error-summary-watcher
  (add-watch state/state ::focus-error-summary
             (fn [_ _ old new]
               (when (and (:focus-error-summary? new)
                          (not (:focus-error-summary? old)))
                 ;; Defer until after the error summary has actually been
                 ;; rendered into the DOM this render pass, then consume the
                 ;; request so it doesn't re-fire on every subsequent render.
                 (js/setTimeout
                   (fn []
                     (focus-error-summary!)
                     (swap! state/state assoc :focus-error-summary? false))
                   0)))))

;; ---------------------------------------------------------------------------
;; Form fields
;; ---------------------------------------------------------------------------

(defn- field-id [section field]
  (str (name section) "-" (name field)))

(defn- text-field
  "One labeled text input bound to `[:form section field]` in `state`. Every
  input has an associated `<label for=...>` (spec 10.6). When the server has
  reported an error for this exact field (spec 10.5), the error is shown
  immediately below the input, linked via `aria-describedby`, and paired
  with a non-color warning symbol so color is never the sole indicator
  (spec 10.6)."
  [{:keys [section field label unit required?]}]
  (let [id (field-id section field)
        path [:form section field]
        error-key (logic/field-error-key section field)
        value (get-in @state/state path)
        errors (get-in @state/state [:field-errors error-key])
        error-id (str id "-error")
        loading? (= :loading (:phase @state/state))]
    [:div.field
     [:label {:for id}
      label (when required? " *")]
     [:span.field-input
      [:input {:id id
               :type "text"
               :value (or value "")
               :disabled loading?
               :aria-required (boolean required?)
               :aria-invalid (boolean (seq errors))
               :aria-describedby (when (seq errors) error-id)
               :on-change (fn [e]
                            (swap! state/state assoc-in path
                                   (.. e -target -value)))}]
      (when unit
        [:span.field-unit " " unit])]
     (when (seq errors)
       [:div.field-error {:id error-id :role "alert"}
        "⚠ " (str/join " " errors)])]))

(defn- address-fields [section legend]
  [:fieldset
   [:legend legend]
   [text-field {:section section :field :name :label "Name" :required? true}]
   [text-field {:section section :field :street-1 :label "Street address" :required? true}]
   [text-field {:section section :field :street-2 :label "Street address line 2 (optional)"}]
   [text-field {:section section :field :city :label "City" :required? true}]
   [text-field {:section section :field :region :label "State / Region" :required? true}]
   [text-field {:section section :field :postal-code :label "Postal code" :required? true}]
   [text-field {:section section :field :country-code :label "Country code" :required? true}]])

(defn- parcel-fields []
  [:fieldset
   [:legend "Parcel measurements"]
   [text-field {:section :parcel :field :weight-kg :label "Weight" :unit "kg" :required? true}]
   [text-field {:section :parcel :field :length-cm :label "Length" :unit "cm" :required? true}]
   [text-field {:section :parcel :field :width-cm :label "Width" :unit "cm" :required? true}]
   [text-field {:section :parcel :field :height-cm :label "Height" :unit "cm" :required? true}]])

;; ---------------------------------------------------------------------------
;; Error summary (spec 10.5, 10.6, AC9)
;; ---------------------------------------------------------------------------

(defn- error-summary []
  (let [{:keys [phase error-message field-errors]} @state/state]
    (cond
      (= phase :error)
      [:div#error-summary.error-summary {:role "alert" :tab-index "-1"}
       [:strong "Error: "] error-message]

      (and (= phase :form) (seq field-errors))
      [:div#error-summary.error-summary {:role "alert" :tab-index "-1"}
       [:strong "Error: "]
       "This shipment could not be quoted. Please fix the highlighted fields below."]

      :else nil)))

;; ---------------------------------------------------------------------------
;; Submission (spec 10.1, AC8)
;; ---------------------------------------------------------------------------

(defn- submit-button []
  (let [loading? (= :loading (:phase @state/state))]
    [:button.button.is-primary
     {:type "submit" :disabled loading?}
     (if loading? "Getting quotes…" "Get shipping quotes")]))

(defn- on-submit [e]
  (.preventDefault e)
  (when-not (= :loading (:phase @state/state))
    (api/submit-quote-request! (:form @state/state))))

(defn- quote-form []
  [:form {:on-submit on-submit}
   [error-summary]
   [address-fields :origin "Origin address"]
   [address-fields :destination "Destination address"]
   [parcel-fields]
   [submit-button]])

;; ---------------------------------------------------------------------------
;; Loading / empty / results states (spec 10.2, 10.3, 10.4, AC7, AC8)
;; ---------------------------------------------------------------------------

(defn- loading-indicator []
  (when (= :loading (:phase @state/state))
    [:div.loading-message {:role "status" :aria-live "polite"}
     "Loading shipping quotes…"]))

(defn- empty-state []
  (when (= :empty (:phase @state/state))
    [:div.empty-state {:role "status" :aria-live "polite"}
     "No shipping services are available for this shipment."]))

(defn- quote-card
  "Shows carrier, service name, total price and currency, estimated
  delivery days (when available), and billable weight (spec 10.3). Base
  price and surcharge are deliberately not shown (spec 10.3: 'Do not
  expose base price and surcharge by default')."
  [{:keys [id carrier serviceName totalAmount currency estimatedDays billableWeightKg]}]
  [:li.quote-card {:key id}
   [:h3 carrier " — " serviceName]
   [:p [:strong "Total: "] totalAmount " " currency]
   (when estimatedDays
     [:p [:strong "Estimated delivery: "]
      estimatedDays " day" (when (not= 1 estimatedDays) "s")])
   [:p [:strong "Billable weight: "] billableWeightKg " kg"]])

(defn- results-list []
  (when (= :results (:phase @state/state))
    ;; Quotes are already in ascending-price order from the server (spec
    ;; 7.5) and are rendered exactly as received -- never re-sorted here.
    [:ol.quotes-list
     (for [quote (:quotes @state/state)]
       ^{:key (:id quote)} [quote-card quote])]))

;; ---------------------------------------------------------------------------
;; Top-level page
;; ---------------------------------------------------------------------------

(defn quotes-page []
  [:div.quotes-page
   [:h1 "Compare shipping quotes"]
   [quote-form]
   [loading-indicator]
   [empty-state]
   [results-list]])
