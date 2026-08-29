(ns parcelwise.http.error-mapping
  "Turns clojure.spec explain-data into the stable, camelCase field-path
  error shape required by spec 9.2:
  `{\"parcel.weightKg\" [\"must be greater than zero\"]}`.

  [Calculation] -- a pure transform: explain-data in, field-path/message
  map out. No I/O, no ring/HTTP machinery.

  clojure.spec.alpha/explain-data's `:in` path for a *value* violation
  nested under s/keys is the un-namespaced keyword path (e.g.
  `[:parcel :weight-kg]`), but for a *missing required key* violation,
  `:in` is only the path to the *containing* map (e.g. `[:origin]`) -- the
  specific missing key name is only recoverable from the problem's `:pred`,
  which for a missing-key check has the literal (unevaluated) shape
  `(fn [%] (contains? % :the-missing-key))`. This namespace special-cases
  that shape (the same technique `expound` uses internally) so a missing
  `name` field still produces the useful, UI-actionable path
  `origin.name` rather than the less useful, ambiguous `origin`."
  (:require
    [clojure.string :as str]))

(defn- missing-key-pred-form
  "If `pred` is the literal, unevaluated `(fn [%] (contains? % k))` form
  clojure.spec generates for a required-key check, returns the missing key
  `k`. Otherwise returns nil."
  [pred]
  (when (and (seq? pred)
             (= 3 (count pred))
             (= 'clojure.core/fn (first pred)))
    (let [body (nth pred 2)]
      (when (and (seq? body)
                 (= 'clojure.core/contains? (first body)))
        (last body)))))

(defn- problem-field-path
  "The full field path for one explain-data problem: the containing-map
  path (`:in`), plus the specific missing key name when this problem is a
  missing-required-key violation (see `missing-key-pred-form`)."
  [{:keys [in pred]}]
  (if-let [missing-key (missing-key-pred-form pred)]
    (conj (vec in) missing-key)
    (vec in)))

(defn- kebab->camel
  [kebab-name]
  (let [[first-part & rest-parts] (str/split kebab-name #"-")]
    (apply str first-part (map str/capitalize rest-parts))))

(defn- field-path->keyword
  [path]
  (keyword (str/join "." (map (comp kebab->camel name) path))))

(defn- problem-message
  [{:keys [pred]}]
  (cond
    (missing-key-pred-form pred)                     "is required"
    (= 'parcelwise.specs/positive-number? pred)       "must be greater than zero"
    (= 'parcelwise.specs/non-blank-string? pred)      "must not be blank"
    :else                                             "is invalid"))

(defn field-errors
  "Turns `explain-data` (the result of `clojure.spec.alpha/explain-data`,
  or nil for a fully valid value) into a map from camelCase dotted-path
  field keyword to a vector of message strings. Every problem in a single
  explain-data pass is reported (spec 9.2, AC2 -- 'return all useful
  boundary-validation errors ... in one pass'). nil maps to `{}`."
  [explain-data]
  (reduce
    (fn [fields problem]
      (let [field-kw (field-path->keyword (problem-field-path problem))
            message (problem-message problem)]
        (update fields field-kw (fnil conj []) message)))
    {}
    (:clojure.spec.alpha/problems explain-data)))
