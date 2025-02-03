(ns clojurecamp.mob.webui
  (:require
   [huff2.core :as h]
   [org.httpkit.server :as http]
   [clojurecamp.mob.log :refer [log!]]
   [clojurecamp.mob.orchestration :refer [mob-progress
                                          ->mob-state
                                          mob-can-start?
                                          mob-can-stop?]]))

(defn page []
  (let [progress (mob-progress)
        state (->mob-state progress)]
    [:html
     [:head
      [:title "Clojure Camp - Mob Server Controller"]]
     [:body
      (cond
        (mob-can-start? state)
        [:button {:onclick "fetch('/start', {method: 'POST'}).then(() => location.reload())"} "Start"]
        (mob-can-stop? state)
        [:button {:onclick "fetch('/stop', {method: 'POST'}).then(() => location.reload())"} "Stop"]
        :else
        "Doing stuff...")
      [:div
       [:pre (pr-str state)]]
      [:table
       [:tbody
        (for [[label status] progress]
          [:tr
           [:td (name label)]
           [:td (if status "✅" "❌")]])]]
      #_[:div
       [:h2 "Log"]
       [:table
        [:tbody
         (for [log-entry (->> (get-log)
                              (sort-by :time)
                              reverse
                              (take 100))]
           [:tr
            [:td [:code (str (:time log-entry))]]
            [:td [:code (:event-type log-entry)]]
            [:td
             (if (:extra-info log-entry)
               [:details
                [:summary (pr-str (:main-info log-entry))]
                [:code
                 (pr-str (:extra-info log-entry))
                 ;; slow
                 [:pre
                  #_(with-out-str
                     (clojure.pprint/pprint (:extra-info log-entry)))]]]
               [:div (pr-str (:main-info log-entry))])]])]]]
      [:script
       ;; auto refresh page
       (when (not (or (mob-can-start? state)
                      (mob-can-stop? state)))
         "setInterval(() => location.reload(), 15000);")]]]))

(defn handler [req]
  (cond
    (= "/" (:uri req))
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html (page)))}

    (= "/start" (:uri req))
    (do
      (log! :message "START")
      #_(mob-start!)
      {:status 200})

    (= "/stop" (:uri req))
    (do
      (log! :message "STOP")
      #_(mob-stop!)
      {:status 200})))

(defonce server (atom nil))

(defn start-server! []
  (when @server
    (@server))
  (reset! server (http/run-server #'handler {:port 9624})))

#_(start-server!)

