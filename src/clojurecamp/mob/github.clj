(ns clojurecamp.mob.github
  (:require
   [org.httpkit.client :as http]
   [cheshire.core :as json]))

(defn get-email [token]
  (-> @(http/request
        {:method :get
         :headers {"Authorization" (str "Bearer " token)}
         :url "https://api.github.com/user/emails"})
      :body
      (json/parse-string keyword)
      (->> (filter :primary)
           first
           :email)))
