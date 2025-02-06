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

(defn running? []
  (boolean @latest-future))

(defn schedule! [f t]
  ;; only allow one "job" at a time
  (when-not (running?)
    (tick! f t)))

(defn cancel! []
  (when @latest-future
    (future-cancel @latest-future)))

#_(running?)
#_(cancel!)

