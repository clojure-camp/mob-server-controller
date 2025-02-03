(ns clojurecamp.mob.cron
  (:require
   [clojurecamp.mob.log :refer [log!]]))

(def latest-future (atom nil))

(defn tick! [f t]
  (try
    (f)
    (catch Exception e
      (log! :exception nil (pr-str e))))
  (reset! latest-future
          ;; TODO should deref?
          (future
            (Thread/sleep t)
            (tick! f t))))

#_(deref latest-future)

#_(future-cancel @latest-future)

