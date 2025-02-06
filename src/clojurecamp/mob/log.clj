(ns clojurecamp.mob.log
  (:require
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [clojure.java.io :as io]))

(defn log! [event-type & [main-info extra-info]]
  (with-open [wrtr (io/writer "log.txt" :append true)]
    (.write wrtr (str (pr-str {:time (str (java.time.ZonedDateTime/now))
                               :event-type event-type
                               :main-info main-info
                               :extra-info extra-info}) "\n"))))

(defn get-log []
  (->> (slurp "log.txt")
       str/split-lines
       (remove str/blank?)
       (map read-string)))

(defn print-log []
  (->> (get-log)
       (map #(select-keys % [:time :event-type]))
       pprint/print-table))

(defn clear-log! []
  (spit "log.txt" "\n"))

#_(clear-log!)
#_(print-log)
#_(log! :test "test")
#_(log! :test "test" {:extra "info"})


