(ns clojurecamp.mob.http
  (:require
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   [clojurecamp.mob.log :refer [log!]]))

(defn http-request
  [{:keys [method url oauth-token body]}]
  (log! :http-request {:method method
                       :url url
                       :body body})
  (-> @(http/request {:method method
                      :url url
                      :oauth-token oauth-token
                      :headers {"Content-Type" "application/json"
                                "Accept" "application/json"}
                      :body (when body
                              (json/generate-string body))})
      ((fn [response]
         (log! :http-response
               {:status (:status response)}
               (-> response
                   ;; don't want to log oauth-token
                   (dissoc :opts)))
         response))
      :body
      (json/parse-string keyword)))
