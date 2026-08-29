(ns parcelwise.domain.calculations
  "Pure domain calculations for shipping-quote weight math (spec sections
  7.1 and 7.2).

  [Calculation] -- every function here depends only on its explicit
  arguments, returns an explicit BigDecimal value, and performs no I/O,
  mutation, or floating-point arithmetic. Money and weight values must
  never use binary floating point (spec 6.2, 13.2).")

(def dimensional-weight-divisor
  "The named divisor used to convert parcel dimensions (in centimeters)
  into dimensional weight (in kilograms). Spec 7.1 requires this to be a
  named domain constant rather than a bare literal scattered through the
  code."
  5000M)

(defn dimensional-weight-kg
  "Dimensional weight in kilograms, computed from parcel dimensions in
  centimeters: (length x width x height) / dimensional-weight-divisor
  (spec 7.1). All arguments and the return value are BigDecimal."
  [length-cm width-cm height-cm]
  (with-precision 20
    (/ (* length-cm width-cm height-cm) dimensional-weight-divisor)))

(defn billable-weight-kg
  "Billable weight in kilograms: the greater of the actual weight and the
  dimensional weight (spec 7.2, AC3, AC4)."
  [actual-weight-kg dimensional-weight-kg]
  (if (>= actual-weight-kg dimensional-weight-kg)
    actual-weight-kg
    dimensional-weight-kg))
