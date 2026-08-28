(ns parcelwise.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[parcelwise started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[parcelwise has shut down successfully]=-"))
   :middleware identity})
