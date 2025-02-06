(ns clojurecamp.mob.cron
  (:require
   [clojurecamp.mob.log :refer [log!]]))

;; :cron/stopped
;; :cron/running
;; :cron/waiting-for-graceful-stop
(defonce state (atom :cron/stopped))
(defonce current-future (atom nil))

(defn tick! [f t]
  (if (= @state :cron/waiting-for-graceful-stop)
    (reset! state :cron/stopped)
    (do
      (try
        (f)
        (catch Exception e
          (log! :exception nil (pr-str e))))
      (reset! current-future
              (future
                (Thread/sleep t)
                (tick! f t))))))

(defn running? []
  (not= @state :cron/stopped))

(defn schedule! [f t]
  (if (= :cron/stopped @state)
    (do
      (reset! state :cron/running)
      (reset! current-future
              (future
                (tick! f t))))
    (throw (ex-info "Already running a job" {}))))

(defn cancel! []
  (when (= @state :cron/running)
    ;; not cancelling directly
    ;; setting state to nil will cause running tick!
    ;; to stop itself next time
    (reset! state :cron/waiting-for-graceful-stop)))

#_(deref state)
#_(running?)
#_(cancel!)

#_(future-cancel @current-future)

#_(schedule! (fn []
               (log! :test "test")
               (Thread/sleep 5000)
               (cancel!))
             5000)

