(ns clojurecamp.mob.orchestration
  (:require
   [clojure.string :as str]
   [clojurecamp.mob.linode :refer [linode-get-instances
                                   linode-get-images
                                   linode-create-instance!
                                   linode-create-image!
                                   linode-shutdown-instance!
                                   linode-delete-instance!
                                   linode-delete-image!
                                   linode-get-disks]]
   [clojurecamp.mob.cloudflare :refer [cloudflare-get-dns-records
                                       cloudflare-create-dns-record!
                                       cloudflare-delete-dns-record!]]
   [clojurecamp.mob.cron :refer [schedule!]]
   [clojurecamp.mob.log :refer [log!]]))

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

(defn mob-create-instance! [region]
  (log! :fn "mob-create-instance!" {:region region})
  (linode-create-instance! {:image (:id (mob-get-latest-image))
                            :type "g6-standard-6"
                            :region region ;; "ca-central" "eu-central"
                            ;; we don't ssh in, so use a random password
                            ;; if things go wrong, can use the virtual terminal from the linode UI
                            :root-pass (str (random-uuid))}))

#_(mob-create-instance! "ca-central")

(defn mob-imagize-disk! []
  (log! :fn "mob-imagize-disk!")
  (let [instance-id (:id (mob-get-instance))
        disk-id (->> (linode-get-disks instance-id)
                     (filter (fn [disk]
                               (= "ext4" (:filesystem disk))))
                     first
                     :id)]
    (linode-create-image! disk-id instance-id)))

#_(mob-imagize-disk!)

(defn mob-shutdown-instance! []
  (log! :fn "mob-shutdown-instance!")
  (linode-shutdown-instance! (:id (mob-get-instance))))

#_(mob-shutdown-instance!)

#_(mob-get-instance)

(defn mob-delete-instance! []
  (log! :fn "mob-delete-instance!")
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
  (log! :fn "mob-trim-images!")
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
  (log! :fn "mob-set-dns-record-ip!" {:ip ip})
  (cloudflare-create-dns-record! {:content ip
                                  :name "mob.clojure.camp"
                                  :proxied true
                                  :type "A"}))

#_(mob-set-dns-record-ip! "139.177.194.5")

(defn mob-delete-dns-record! []
  (log! :fn "mob-delete-dns-record!")
  (cloudflare-delete-dns-record! (:id (mob-get-dns-record))))

#_(mob-delete-dns-record!)

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
    ^{:ip (first (:ipv4 dns-record))}
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
     [:mob.progress/ip-reset (and
                               (= "offline" (:status instance))
                               (nil? dns-record))]
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

(defn mob-can-start? [state]
  (= :mob.progress/system-offline state))

(defn mob-start! [region]
  (log! :fn "mob-start!" {:region region})
  (if (mob-can-start? (mob-state))
    (mob-create-instance! region)
    (log! :msg "(already starting)"))
  ;; polling-logic! does the rest:
  ;;   set dns ip
  )

(defn mob-can-stop? [state]
  (= :mob.progress/system-online state))

(defn mob-stop! []
  (log! :fn "mob-stop!")
  (if (mob-can-stop? (mob-state))
    (mob-shutdown-instance!)
    (log! :msg "(already stopping)"))
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
    (mob-set-dns-record-ip! (first (:ipv4 (mob-get-instance))))

    ;; shutdown
    ;; shutdown-instance! is not here, because it is manually triggered
    :mob.progress/server-stopped
    (mob-delete-dns-record!)
    :mob.progress/ip-reset
    (mob-imagize-disk!)
    :mob.progress/image-created
    (mob-trim-images!)
    :mob.progress/images-trimmed
    (mob-delete-instance!)

    nil))

(defn do-stuff! []
  (let [p (mob-progress)]
    (log! :state (->mob-state p))
    (polling-logic! p)))

(defn start-poller! []
  ;; starts "poller" job every 15s s
  ;; should run slower than every 5s
  ;; because may have race conditions with how progress and loop interacts
  (schedule! do-stuff! 15000))
