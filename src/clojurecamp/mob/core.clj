(ns clojurecamp.mob.core
  (:gen-class)
  (:require
   [clojurecamp.mob.log :refer [print-log]]
   [clojurecamp.mob.webui :as webui]
   [clojurecamp.mob.orchestration :refer [mob-create-instance!
                                          mob-imagize-disk!
                                          mob-shutdown-instance!
                                          mob-delete-instance!
                                          mob-get-instance
                                          mob-start!
                                          mob-stop!
                                          mob-trim-images!
                                          mob-set-dns-record-ip!]
    :as orch]))


(defn -main []
  (orch/start-poller!)
  (webui/start-server!))

(comment
  (mob-start!)
  (mob-stop!)
  (print-log))

;; manual orchestration

(comment
  ;; start
  (mob-create-instance!)
  (mob-set-dns-record-ip! (first (:ipv4 (mob-get-instance))))

  ;; stop
  (mob-shutdown-instance!)
  ;; (wait)
  (mob-imagize-disk!)
  ;; (wait)
  (mob-trim-images!)
  (mob-delete-instance!))
