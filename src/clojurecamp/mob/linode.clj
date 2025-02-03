(ns clojurecamp.mob.linode
  (:require
   [clojurecamp.mob.http :refer [http-request]]
   [clojurecamp.mob.config :refer [config]]))

(defn linode-request
  [{:keys [method url body]}]
  (http-request {:method method
                 :url (str "https://api.linode.com/v4" url)
                 :oauth-token (config :linode-token)
                 :body body}))

(defn linode-list-regions []
  ;; https://techdocs.akamai.com/linode-api/reference/get-regions
  (->> (linode-request {:method :get
                        :url "/regions"})
       :data
       ;; {:label "..." :id "..."}
       ))

#_(->> (linode-list-regions)
       (filter #(= "Toronto, CA" (:label %)))
       first
       :id)
#_(->> (linode-list-regions)
       (filter #(= "Frankfurt, DE" (:label %)))
       first
       :id)

(defn linode-list-types []
  ;; https://techdocs.akamai.com/linode-api/reference/get-linode-types
  (->> (linode-request {:method :get
                        :url "/linode/types"})
       :data
       ;; {:label "..." :id "..."}
       ))

#_(->> (linode-list-types)
       (filter #(= "Linode 16GB" (:label %)))
       first
       :id)

(defn linode-get-images []
  ;; https://techdocs.akamai.com/linode-api/reference/get-images
  (->> (linode-request {:method :get
                        :url "/images"})

       :data
       (remove :is_public)
       ;; {:label "..." :updated "..."}
       ))

#_(linode-get-images)

(defn linode-get-instances []
  ;; https://techdocs.akamai.com/linode-api/reference/get-linode-instances
  (->> (linode-request {:method :get
                        :url "/linode/instances"})
       :data
       ;; {:id ..., ...}
       ))

#_(linode-get-instances)

(defn linode-create-instance! [{:keys [image region type root-pass]}]
  ;; https://techdocs.akamai.com/linode-api/reference/post-linode-instance
  (linode-request {:method :post
                   :url "/linode/instances"
                   :body {:image image
                          :type type
                          :root_pass root-pass
                          :region region}}))

(defn linode-get-disks [linode-id]
  ;; https://techdocs.akamai.com/linode-api/reference/get-linode-disks
  (->> (linode-request {:method :get
                        :url (str "/linode/instances/" linode-id "/disks")})
      :data
      ;; {:id ..., ...}
      ))

(defn linode-shutdown-instance! [instance-id]
  ;; https://techdocs.akamai.com/linode-api/reference/post-shutdown-linode-instance
  (linode-request {:method :post
                   :url (str "/linode/instances/" instance-id "/shutdown")}))

(defn linode-delete-instance! [instance-id]
  ;; https://techdocs.akamai.com/linode-api/reference/delete-linode-instance
  (linode-request {:method :delete
                   :url (str "/linode/instances/" instance-id)}))

(defn linode-create-image!
  [disk-id description]
  ;; https://techdocs.akamai.com/linode-api/reference/post-image
  (linode-request {:method :post
                   :url "/images"
                   :body {:disk_id disk-id
                          :description (str description)
                          :label (str "mob-"
                                      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm")
                                               (java.time.ZonedDateTime/now)))}}))

(defn linode-delete-image!
  [image-id]
  ;; https://techdocs.akamai.com/linode-api/reference/delete-image
  (linode-request {:method :delete
                   :url (str "/images/" image-id)}))


