(require '[org.httpkit.client :as http])

;; secrets -------

(defn config [k]
  (-> "config.edn"
      slurp
      read-string
      (get k)))

#_(config :cloudflare-token)

;; expects:
;;  :cloudflare-token
;;    https://dash.cloudflare.com/profile/api-tokens
;;  :cloudflare-zone-id
;;    find using cloudflare-zone-id below
;;  :linode-token
;;    https://cloud.linode.com/profile/tokens

;; logging --------

(defn log! [data]
  (with-open [wrtr (io/writer "log.txt" :append true)]
    (.write wrtr (str (pr-str {:time (str (java.time.ZonedDateTime/now))
                          :data data}) "\n"))))

(defn print-log []
  (->> (slurp "log.txt")
       str/split-lines
       (map read-string)
       (map #(select-keys % [:time :data]))
       clojure.pprint/print-table))

(defn clear-log! []
  (spit "log.txt" ""))

#_(clear-log!)
#_(print-log)
#_(log! "test")

;; cloudflare --------

(defn cloudflare-request
  [{:keys [method url body]}]
  (-> @(http/request {:method method
                      :url (str "https://api.cloudflare.com/client/v4" url)
                      :headers {"Authorization" (str "Bearer " (config :cloudflare-token))
                                "Accept" "application/json"}
                      :body body})
      :body
      (json/parse-string keyword)))

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
                            :body (json/generate-string {:content ip})})
       :result))

;; linode ----------

(defn linode-request
  [{:keys [method url body]}]
  (-> @(http/request {:method method
                      :url (str "https://api.linode.com/v4" url)
                      :headers {"authorization" (str "Bearer " (config :linode-token))
                                "accept" "application/json"
                                "content-type" "application/json"}
                      :body body})
      :body
      (json/parse-string keyword)))

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
                   :body (json/generate-string
                          {:image image
                           :type type
                           :root_pass root-pass
                           :region region})}))

(defn linode-get-disks [linode-id]
  ;; https://techdocs.akamai.com/linode-api/reference/get-linode-disks
  (->> (linode-request {:method :Get
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
                   :body (json/generate-string
                          {:disk_id disk-id
                           :description (str description)
                           :label (str "mob-"
                                       (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm")
                                                (java.time.ZonedDateTime/now)))})}))

(defn linode-delete-image!
  [image-id]
  ;; https://techdocs.akamai.com/linode-api/reference/delete-image
  (linode-request {:method :delete
                   :url (str "/images/" image-id)}))

;; mob utility functions --------
;; (can have mob specific functions here)
;; these functions assume only one instance is running

(defn mob-get-instance []
  (first (linode-get-instances)))

#_(mob-get-instance)

(defn mob-images []
  (->> (linode-get-images)
       (remove (fn [image]
                 (= "automatic" (:type image))))))

#_(mob-images)

(defn mob-get-latest-image []
  (->> (mob-images)
       (sort-by :created)
       last))

(defn mob-create-instance! []
  (linode-create-instance! {:image (:id (mob-get-latest-image))
                            :type "g6-standard-6"
                            :region "ca-central"
                            ;; we don't ssh in, so use a random password
                            ;; if things go wrong, can use the virtual terminal from the linode UI
                            :root-pass (str (random-uuid))}))

#_(mob-create-instance!)

(defn mob-imagize-disk! []
  (let [instance-id (:id (mob-get-instance))
        disk-id (->> (linode-get-disks instance-id)
                     (filter (fn [disk]
                               (= "ext4" (:filesystem disk))))
                     first
                     :id)]
    (linode-create-image! disk-id instance-id)))

#_(mob-imagize-disk!)

(defn mob-shutdown-instance! []
  (linode-shutdown-instance! (:id (mob-get-instance))))

#_(mob-shutdown-instance!)

#_(mob-get-instance)

(defn mob-delete-instance! []
  (linode-delete-instance! (:id (mob-get-instance))))

#_(mob-delete-instance!)

(defn mob-images-to-delete []
  (->> (linode-get-images)
       ;; images labeled "keepme*" are always kept
       (remove (fn [image]
                 (str/starts-with? (:label image) "keepme")))
       ;; temporary automated backup images are always kept
       (remove (fn [image]
                 (= "automatic" (:type image))))
       ;; keep the most recent one
       (sort-by :created)
       ;; reverse??? TODO DOUBLE CHECK
       butlast))

(defn mob-trim-images!
  "Removes images, except: ones marked 'keepme' and the latest image"
  []
  (doseq [image (mob-images-to-delete)]
    (linode-delete-image! (:id image))))

#_(mob-trim-images!)

(defn mob-get-dns-record []
  (->> (cloudflare-get-dns-records)
       (filter (fn [record]
                 (and
                   (= "A" (:type record))
                   (= "mob.clojure.camp" (:name record)))))
       first)
  ;; :id, :content (the ip address)
  )

#_(mob-get-dns-record)

(defn mob-set-dns-record-ip! [ip]
  (cloudflare-set-dns-record! (:id (mob-get-dns-record))
                              ip))

#_(mob-set-dns-record-ip! "139.177.194.5")

;; mob state machine -------

;; this scripts helps boot up the mob system and shut it down
;;   boot
;;     create an instance on linode (takes ~4min)
;;     set the dns record (via cloudflare) to the ip of the instance (~instant)
;;  shutdown
;;    shutdown the instance on linode (takes ~30sec)
;;    create a disk image (takes ~6min)
;;
;; multiple interdependent parts work *together* to achieve our goal
;;   mob-progress
;;     retrieves various states from linode and cloudflare
;;   mob-state
;;     determines the current overall state of the system
;;   mob-start! and mob-stop! functions
;;     triggers changes on linode
;;   polling-loop
;;     checks the current overall state and triggers changes (to transition to next state)
;;
;; because they are interdependent, care must be taken when making changes to any one of them
;;
;; timeline:
;;
;;   :mob.progress/system-offline
;;      |
;;      | [user trigger] mob-start!
;;      | [linode] instance starts provisioning and booting
;;      V
;;   :mob.progress/starting-server
;;      |
;;      | [linode] (takes ~4min)
;;      V
;;   :mob.progress/server-started
;;      |
;;      | [poll trigger] mob-set-dns-record-ip!
;;      | [cloudflare] sets dns record to ip of instance (~instant)
;;      V
;;   :mob.progress/system-online
;;      |
;;      | [user trigger] mob-stop!
;;      | [linode] instance starts shutting down
;;      V
;;   :mob.progress/stopping-server
;;      |
;;      | [linode] (takes ~30sec)
;;      V
;;   :mob.progress/server-stopped
;;      |
;;      | [poll trigger] mob-imagize-disk!
;;      | [linode] starts creating image of disk
;;      V
;;   :mob.progress/creating-image
;;      |
;;      | [linode] (takes ~6min)
;;      V
;;   :mob.progress/image-created
;;      |
;;      | [poll trigger] mob-trim-images! (deletes old images)
;;      V
;;   :mob.progress/images-trimmed
;;      |
;;      | [poll trigger] mob-delete-instance!
;;      | [linode] deletes instance (~instant)
;;      V
;;   :mob.progress/system-offline
;;
;; the two "stable" states of the system are:
;;    :mob.progress/system-offline
;;    :mob.progress/system-online
;; (ie. without intervention, they will stay that way)
;;
;; known issues:
;;   calling mob-start! in rapid succession can still start two instances

(defn mob-progress []
  (let [instance (mob-get-instance)
        dns-record (mob-get-dns-record)
        images (mob-images)
        images-to-delete (mob-images-to-delete)
        ip-of-active-instance? (= (:content dns-record)
                                  (first (:ipv4 instance)))
        image-of-active-instance? (->> images
                                       (filter (fn [image]
                                                 (and
                                                   (= "available" (:status image))
                                                   ;; we explicitly store the id of instance
                                                   ;; as description when creating image
                                                   (= (str (:id instance)) (:description image)))))
                                       seq
                                       boolean)]
    [[:mob.progress/system-offline true]
     [:mob.progress/starting-server (or (= "booting" (:status instance))
                                        (= "provisioning" (:status instance)))]
     [:mob.progress/server-started (= "running" (:status instance))]
     [:mob.progress/ip-set ip-of-active-instance?]
     [:mob.progress/system-online (and
                                   (= "running" (:status instance))
                                   ip-of-active-instance?)]
     ;; -------
     [:mob.progress/stopping-server (= "shutting_down" (:status instance))]
     [:mob.progress/server-stopped (= "offline" (:status instance))]
     [:mob.progress/creating-image (->> images
                                        (some (fn [image]
                                                (= "creating" (:status image))))
                                        boolean)]
     [:mob.progress/image-created image-of-active-instance?]
     [:mob.progress/images-trimmed (and
                                     (= "offline" (:status instance))
                                     (empty? images-to-delete)
                                     image-of-active-instance?)]
     ;; very short lived
     [:mob.progress/deleting-server (= "deleting" (:status instance))]]))

#_(mob-progress)

(defn ->mob-state [progress]
  (->> progress
       reverse
       (filter (fn [[_ v]]
                 v))
       first
       first))

(defn mob-state []
  (->> (mob-progress)
       ->mob-state))

#_(mob-state)

(defn mob-start! []
  (log! "mob-start!")
  (if (= :mob.progress/system-offline (mob-state))
    (do
      (log! "mob-create-instance!")
      (mob-create-instance!))
    (log! "(already starting)"))
  ;; polling-logic! does the rest:
  ;;   set dns ip
  )

(defn mob-stop! []
  (log! "mob-stop!")
  (if (= :mob.progress/system-online (mob-state))
    (do
      (log! "mob-shutdown-instance!")
      (mob-shutdown-instance!))
    (log! "(already stopping)"))
  ;; polling-logic! does the rest:
  ;;   stop server
  ;;   create image
  ;;   (wait 6+ min)
  ;;   delete server
  ;;   trim images
  )

(defn polling-logic! [p]
  (case (->mob-state p)
    ;; start
    ;; create-instance! is not here, because it is manually triggered
    :mob.progress/server-started
    (do
      (log! "mob-set-dns-record-ip!")
      (mob-set-dns-record-ip! (first (:ipv4 (mob-get-instance)))))

    ;; shutdown
    ;; shutdown-instance! is not here, because it is manually triggered
    :mob.progress/server-stopped
    (do
      (log! "mob-imagize-disk!")
      (mob-imagize-disk!))
    :mob.progress/image-created
    (do
       (log! "mob-trim-images!")
       (mob-trim-images!))
    :mob.progress/images-trimmed
    (do
      (log! "mob-delete-instance!")
      (mob-delete-instance!))

    nil))

;; html

(defn page []
  (let [progress (mob-progress)]
    [:body
     [:table
      [:tbody
       (for [[label status] progress]
         [:tr
          [:td (name label)]
          [:td (if status "✅" "❌")]])]]]))

;; "cron" --------

(def latest-future (atom nil))

(defn tick! [f t]
  (f)
  (reset! latest-future
          ;; TODO should deref?
          (future
            (Thread/sleep t)
            (tick! f t))))

#_(deref latest-future)

#_(future-cancel @latest-future)

;; ----------------

;; automatic

(defn do-stuff! []
  (let [p (mob-progress)]
    (log! (->mob-state p))
    (polling-logic! p)))

;; should run slower than every 5s
;; because may have race conditions with how progress and loop interacts
#_(tick! do-stuff! 15000)

#_(mob-start!)
#_(mob-stop!)

#_(print-log)

;; manual
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

;; TODO
;;   start! and stop! should set a polling-active? atom
;;   when this script is run, set value of polling-active? based on a request to mob-state
;;   pull secrets from config.edn
;;   UI
;;   ability to specify locale for launch
;;   multiple namespaces

