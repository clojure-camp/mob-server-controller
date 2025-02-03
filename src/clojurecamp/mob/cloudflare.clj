(ns clojurecamp.mob.cloudflare
  (:require
   [clojurecamp.mob.http :refer [http-request]]
   [clojurecamp.mob.config :refer [config]]))

(defn cloudflare-request
  [{:keys [method url body]}]
  (http-request {:method method
                 :url (str "https://api.cloudflare.com/client/v4" url)
                 :oauth-token (config :cloudflare-token)
                 :body body}))

(defn cloudflare-get-zone-id [domain]
  ;; https://developers.cloudflare.com/api/resources/zones/methods/list/
  (->> (cloudflare-request {:method :get
                            :url "/zones"})
       :result
       (filter (fn [x]
                 (= domain (:name x))))
       first
       :id))

#_(cloudflare-get-zone-id "clojure.camp")

(defn cloudflare-get-dns-records []
  ;; https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/list/
  (->> (cloudflare-request {:method :get
                            :url (str "/zones/" (config :cloudflare-zone-id) "/dns_records")})
      :result))

#_(cloudflare-get-dns-records)

(defn cloudflare-set-dns-record!
  [record-id ip]
  (->> (cloudflare-request {:method :patch
                            :url (str "/zones/" (config :cloudflare-zone-id) "/dns_records/" record-id)
                            :body {:content ip}})
       :result))


